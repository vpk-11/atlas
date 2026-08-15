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
    // can admit far more concurrent requests than postOsrmExecutor (30 workers,
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

    public CompletableFuture<ResponseEntity<RideResponse>> requestRide(RideRequest request) {
        Coordinate pickup = new Coordinate(request.pickup().lat(), request.pickup().lng());
        Coordinate drop = new Coordinate(request.drop().lat(), request.drop().lng());

        // Durability step, not a new API behavior: write REQUESTED before any matching/
        // pricing work so a mid-match crash still leaves a record of the attempt. If this
        // write itself fails there's nothing to update later, so bail immediately.
        // Fast (gRPC + local DB), stays synchronous - not the bottleneck, no reason to
        // move it off the request thread.
        String tripId;
        try {
            tripId = tripClient.recordRequested(request.riderId(), pickup, drop);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while recording REQUESTED for rider {}: {}", request.riderId(), e.toString());
            return ok(RideResponse.systemError("Unable to process this ride, try again shortly"));
        }

        List<CandidateScore> ranked;
        try {
            ranked = matchingService.rankCandidates(pickup);
        } catch (Exception e) {
            log.error("Driver DB unreachable while ranking candidates for rider {}: {}", request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId);
            return ok(RideResponse.systemError("Unable to process this ride, try again shortly"));
        }

        if (ranked.isEmpty()) {
            return ok(noMatch(request.riderId(), tripId));
        }

        CandidateScore winner = ranked.get(0);

        // Admission gate: everything past this point (OSRM call + pricing/assignment/
        // finalize on postOsrmExecutor) consumes bounded downstream capacity. Fail fast
        // if the system is already at capacity rather than queueing silently.
        if (!admissionGate.tryAcquire()) {
            log.warn("Admission gate at capacity ({} permits), rejecting ride for rider {}",
                    ADMISSION_PERMITS, request.riderId());
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(RideResponse.systemError("System at capacity, please retry")));
        }

        long gateAcquiredAt = System.nanoTime();
        return osrmClient.distanceAndDuration(pickup, drop)
                .toFuture()
                .thenApplyAsync(tripDistance ->
                        finishMatch(request, tripId, pickup, drop, winner, tripDistance), postOsrmExecutor)
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

    private RideResponse finishMatch(RideRequest request, String tripId, Coordinate pickup, Coordinate drop,
                                      CandidateScore winner, DistanceResult tripDistance) {
        double price;
        try {
            price = pricingClient.getQuote(pickup, drop, tripDistance);
        } catch (PricingClient.PricingUnavailableException e) {
            log.error("Pricing unreachable for rider {}: {}", request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId);
            return RideResponse.systemError("Unable to price this ride, try again shortly");
        }

        try {
            driverAssignmentService.assignTrip(winner.driverId(), drop, tripDistance.durationMinutes());
        } catch (Exception e) {
            log.error("Driver DB unreachable while assigning driver {} for rider {}: {}",
                    winner.driverId(), request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId);
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        try {
            tripClient.finalizeMatchedTrip(tripId, winner.driverId(), price, tripDistance);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable for rider {} after driver {} assigned: {}",
                    request.riderId(), winner.driverId(), e.toString());
            return RideResponse.systemError("Match succeeded but could not be recorded, try again shortly");
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

    private RideResponse noMatch(String riderId, String tripId) {
        try {
            tripClient.finalizeFailedNoMatch(tripId);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while logging FAILED_NO_MATCH for rider {}: {}", riderId, e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }
        meterRegistry.counter("ride_match_outcomes_total", "outcome", "failed_no_match").increment();
        return RideResponse.failedNoMatch("No available or soon-to-be-available drivers found");
    }

    private void logSystemError(String riderId, String tripId) {
        try {
            tripClient.finalizeSystemError(tripId);
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
