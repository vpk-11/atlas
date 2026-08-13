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
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RideService {

    private static final Logger log = LoggerFactory.getLogger(RideService.class);

    private final MatchingService matchingService;
    private final OsrmClient osrmClient;
    private final PricingClient pricingClient;
    private final TripClient tripClient;
    private final DriverAssignmentService driverAssignmentService;
    private final MeterRegistry meterRegistry;

    public RideService(MatchingService matchingService, OsrmClient osrmClient, PricingClient pricingClient,
                        TripClient tripClient, DriverAssignmentService driverAssignmentService,
                        MeterRegistry meterRegistry) {
        this.matchingService = matchingService;
        this.osrmClient = osrmClient;
        this.pricingClient = pricingClient;
        this.tripClient = tripClient;
        this.driverAssignmentService = driverAssignmentService;
        this.meterRegistry = meterRegistry;
    }

    public RideResponse requestRide(RideRequest request) {
        Coordinate pickup = new Coordinate(request.pickup().lat(), request.pickup().lng());
        Coordinate drop = new Coordinate(request.drop().lat(), request.drop().lng());

        // Durability step, not a new API behavior: write REQUESTED before any matching/
        // pricing work so a mid-match crash still leaves a record of the attempt. If this
        // write itself fails there's nothing to update later, so bail immediately.
        String tripId;
        try {
            tripId = tripClient.recordRequested(request.riderId(), pickup, drop);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while recording REQUESTED for rider {}: {}", request.riderId(), e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        List<CandidateScore> ranked;
        try {
            ranked = matchingService.rankCandidates(pickup);
        } catch (Exception e) {
            log.error("Driver DB unreachable while ranking candidates for rider {}: {}", request.riderId(), e.toString());
            logSystemError(request.riderId(), tripId);
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        if (ranked.isEmpty()) {
            return noMatch(request.riderId(), tripId);
        }

        CandidateScore winner = ranked.get(0);
        DistanceResult tripDistance = osrmClient.distanceAndDuration(pickup, drop);

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

    public CancelResponse cancelTrip(String tripId) {
        String driverId = tripClient.cancelTrip(tripId);
        if (driverId != null && !driverId.isBlank()) {
            driverAssignmentService.freeDriverForTrip(driverId);
        }
        return new CancelResponse("CANCELLED", tripId);
    }
}
