package com.atlas.dispatchservice.ride;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.driver.DriverAssignmentService;
import com.atlas.dispatchservice.driver.DriverStatus;
import com.atlas.dispatchservice.matching.CandidateScore;
import com.atlas.dispatchservice.matching.MatchingService;
import com.atlas.dispatchservice.osrm.OsrmClient;
import com.atlas.dispatchservice.pricing.PricingClient;
import com.atlas.dispatchservice.trip.TripClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    // Admission gate for the OSRM-onward pipeline (OSRM call + postOsrmExecutor
    // stage). Prevents the failure mode found in the first async attempt: Tomcat
    // can admit far more concurrent requests than postOsrmExecutor (a bounded pool,
    // see AsyncConfig) can drain, so without a gate, excess requests queued
    // silently for 10s+ instead of failing fast - zero throughput gain, worse
    // latency than the blocking version it replaced.
    //
    // Sized via Little's Law using the real bounded quantities in this system,
    // not a guess: OSRM's own call now has a single hard 350ms budget
    // (osrm.timeout-ms, see OsrmClient), and postOsrmExecutor's own service
    // time is small (2 in-cluster gRPC calls + 1 DB write). Measured via the
    // finishMatch timing log below under real load: healthy (non-backlogged)
    // total OSRM-onward latency runs ~350-450ms. Target = original 500 req/sec
    // goal x ~0.45s expected healthy latency ~= 225, rounded up to 250 for
    // headroom. This is the system's real admitted-concurrency ceiling now,
    // not an arbitrary throttle - see decisions.md for the load-test numbers
    // that confirmed it.
    static final int ADMISSION_PERMITS = 250; // package-private: referenced directly by RideServiceTest

    private final MatchingService matchingService;
    private final OsrmClient osrmClient;
    private final PricingClient pricingClient;
    private final TripClient tripClient;
    private final DriverAssignmentService driverAssignmentService;
    private final MeterRegistry meterRegistry;
    private final Executor postOsrmExecutor;
    private final Semaphore admissionGate = new Semaphore(ADMISSION_PERMITS);

    public RideService(MatchingService matchingService, OsrmClient osrmClient, PricingClient pricingClient,
                        TripClient tripClient, DriverAssignmentService driverAssignmentService,
                        MeterRegistry meterRegistry, @Qualifier("postOsrmExecutor") Executor postOsrmExecutor) {
        this.matchingService = matchingService;
        this.osrmClient = osrmClient;
        this.pricingClient = pricingClient;
        this.tripClient = tripClient;
        this.driverAssignmentService = driverAssignmentService;
        this.meterRegistry = meterRegistry;
        this.postOsrmExecutor = postOsrmExecutor;
    }

    public CompletableFuture<ResponseEntity<RideResponse>> requestRide(RideRequest request, String idempotencyKey) {
        Coordinate pickup = new Coordinate(request.pickup().lat(), request.pickup().lng());
        Coordinate drop = new Coordinate(request.drop().lat(), request.drop().lng());

        // Durability step, not a new API behavior: write REQUESTED before any matching/
        // pricing work so a mid-match crash still leaves a record of the attempt. If this
        // write itself fails there's nothing to update later, so bail immediately.
        // Fast (gRPC + local DB), stays synchronous - not the bottleneck, no reason to
        // move it off the request thread.
        TripClient.RecordedTrip recorded;
        try {
            recorded = tripClient.recordRequested(request.riderId(), pickup, drop, idempotencyKey);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while recording REQUESTED for rider {}: {}", request.riderId(), e.toString());
            return ok(RideResponse.systemError("Unable to process this ride, try again shortly"));
        }
        String tripId = recorded.tripId();
        String token = recorded.claimToken();

        // Replay of an idempotency key. Settled outcomes are returned as stored, with no
        // matching/pricing/assignment. An unsettled row (REQUESTED or retriable SYSTEM_ERROR)
        // resumes the match only if trip-service granted this caller the claim; otherwise the
        // original request may still be running, so answer 409 rather than assign a second driver.
        if (recorded.replayed()) {
            switch (recorded.status()) {
                case MATCHED -> {
                    // driver_status, eta fields are not stored on the trip row.
                    return ok(RideResponse.matched(tripId, recorded.driverId(), null,
                            request.pickup(), request.drop(), recorded.price(), 0, null));
                }
                case FAILED_NO_MATCH -> {
                    return ok(RideResponse.failedNoMatch("No available or soon-to-be-available drivers found"));
                }
                case CANCELLED -> {
                    return ok(RideResponse.cancelled(tripId, "Trip for this idempotency key was cancelled"));
                }
                default -> {
                    if (!recorded.resumeGranted()) {
                        log.info("Replay of in-flight trip {} (status {}), not resuming", tripId, recorded.status());
                        return CompletableFuture.completedFuture(ResponseEntity.status(HttpStatus.CONFLICT)
                                .header("Retry-After", "2")
                                .body(RideResponse.inProgress(tripId, "A request with this idempotency key is still in progress")));
                    }
                    log.info("Resuming replayed trip {} (status {})", tripId, recorded.status());
                }
            }
        }

        List<CandidateScore> ranked;
        try {
            ranked = matchingService.rankCandidates(pickup);
        } catch (Exception e) {
            log.error("Driver DB unreachable while ranking candidates for rider {}: {}", request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId, token);
            return ok(RideResponse.systemError("Unable to process this ride, try again shortly"));
        }

        if (ranked.isEmpty()) {
            return ok(noMatch(request.riderId(), tripId, token));
        }

        CandidateScore winner = ranked.get(0);

        // Admission gate: everything past this point (OSRM call + pricing/assignment/
        // finalize on postOsrmExecutor) consumes bounded downstream capacity. Fail fast
        // if the system is already at capacity rather than queueing silently.
        if (!admissionGate.tryAcquire()) {
            log.warn("Admission gate at capacity ({} permits), rejecting ride for rider {}",
                    ADMISSION_PERMITS, request.riderId());
            // Fire-and-forget: the REQUESTED row already written above still needs
            // finalizing, but this must stay fast (the whole point of fail-fast), so
            // don't block the response on it, and don't use postOsrmExecutor - that's
            // the pool most likely to also be saturated right when this path fires.
            CompletableFuture.runAsync(() -> logSystemError(request.riderId(), tripId, token));
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(RideResponse.systemError("System at capacity, please retry")));
        }

        long gateAcquiredAt = System.nanoTime();
        return osrmClient.distanceAndDuration(pickup, drop)
                .toFuture()
                .thenApplyAsync(tripDistance ->
                        finishMatch(request, tripId, token, pickup, drop, winner, tripDistance), postOsrmExecutor)
                .handle((response, throwable) -> {
                    admissionGate.release();
                    log.debug("OSRM-onward pipeline took {}ms for rider {}",
                            (System.nanoTime() - gateAcquiredAt) / 1_000_000, request.riderId());

                    if (throwable == null) {
                        return ok0(response);
                    }
                    // postOsrmExecutor has zero queue capacity (see AsyncConfig) - a full
                    // pool rejects immediately rather than queueing, so this is a fast,
                    // expected capacity signal, not a bug. Same 503 semantics as the
                    // admission gate above, just caught one layer deeper.
                    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                    // Same reasoning as the admission-gate rejection above: finalize the
                    // REQUESTED row without blocking this already-failing response on it.
                    CompletableFuture.runAsync(() -> logSystemError(request.riderId(), tripId, token));
                    if (cause instanceof TaskRejectedException) {
                        log.warn("Post-OSRM executor at capacity, rejecting ride for rider {}: {}",
                                request.riderId(), cause.toString());
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body(RideResponse.systemError("System at capacity, please retry"));
                    }
                    log.error("Unexpected failure in OSRM-onward pipeline for rider {}: {}",
                            request.riderId(), throwable.toString());
                    return ok0(RideResponse.systemError("Unable to process this ride, try again shortly"));
                });
    }

    private RideResponse finishMatch(RideRequest request, String tripId, String token, Coordinate pickup, Coordinate drop,
                                      CandidateScore winner, DistanceResult tripDistance) {
        double price;
        try {
            price = pricingClient.getQuote(pickup, drop, tripDistance);
        } catch (PricingClient.PricingUnavailableException e) {
            log.error("Pricing unreachable for rider {}: {}", request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId, token);
            return RideResponse.systemError("Unable to price this ride, try again shortly");
        }

        try {
            driverAssignmentService.assignTrip(winner.driverId(), drop, tripDistance.durationMinutes());
        } catch (Exception e) {
            log.error("Driver DB unreachable while assigning driver {} for rider {}: {}",
                    winner.driverId(), request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId, token);
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        TripClient.UpdateOutcome outcome;
        try {
            outcome = tripClient.finalizeMatchedTrip(tripId, token, winner.driverId(), price, tripDistance);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable for rider {} after driver {} assigned: {}",
                    request.riderId(), winner.driverId(), e.toString());
            return RideResponse.systemError("Match succeeded but could not be recorded, try again shortly");
        }
        if (!outcome.applied()) {
            if (outcome.currentStatus() == com.atlas.dispatchservice.grpc.trip.TripStatus.CANCELLED) {
                // Cancelled while the match ran: cancel could not know the driver yet, so free it here.
                log.info("Trip {} was cancelled mid-match, freeing driver {}", tripId, winner.driverId());
                driverAssignmentService.freeDriverForTrip(winner.driverId());
                return RideResponse.cancelled(tripId, "Trip was cancelled while matching");
            }
            // Another request holds the claim now (lease overrun); it owns the outcome. Release the
            // driver this request assigned, unless the new owner ended up with the same one.
            log.warn("Trip {} outcome dropped (status {}): claim superseded", tripId, outcome.currentStatus());
            if (!winner.driverId().equals(outcome.currentDriverId())) {
                driverAssignmentService.freeDriverForTrip(winner.driverId());
            }
            return RideResponse.systemError("Request superseded by a retry, try again shortly");
        }

        String etaNote = null;
        if (winner.effectiveStatus() == DriverStatus.ON_TRIP && winner.currentTripRemainingMinutes() != null) {
            etaNote = "Driver finishing another trip, arriving in %d min, will reach you in %d min".formatted(
                    Math.round(winner.currentTripRemainingMinutes()), Math.round(winner.totalTimeToPickupMinutes()));
        }

        meterRegistry.counter("ride_match_outcomes_total", "outcome", "matched").increment();
        return RideResponse.matched(tripId, winner.driverId(), winner.effectiveStatus().name(),
                request.pickup(), request.drop(), price, (int) Math.round(winner.totalTimeToPickupMinutes()), etaNote);
    }

    private RideResponse noMatch(String riderId, String tripId, String token) {
        try {
            tripClient.finalizeFailedNoMatch(tripId, token);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while logging FAILED_NO_MATCH for rider {}: {}", riderId, e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }
        meterRegistry.counter("ride_match_outcomes_total", "outcome", "failed_no_match").increment();
        return RideResponse.failedNoMatch("No available or soon-to-be-available drivers found");
    }

    private void logSystemError(String riderId, String tripId, String token) {
        try {
            tripClient.finalizeSystemError(tripId, token);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip also unreachable while logging SYSTEM_ERROR for rider {}: {}", riderId, e.toString());
        }
    }

    private CompletableFuture<ResponseEntity<RideResponse>> ok(RideResponse response) {
        return CompletableFuture.completedFuture(ok0(response));
    }

    private ResponseEntity<RideResponse> ok0(RideResponse response) {
        return ResponseEntity.ok(response);
    }

    public CancelResponse cancelTrip(String tripId) {
        String driverId = tripClient.cancelTrip(tripId);
        if (driverId != null && !driverId.isBlank()) {
            driverAssignmentService.freeDriverForTrip(driverId);
        }
        return new CancelResponse("CANCELLED", tripId);
    }
}
