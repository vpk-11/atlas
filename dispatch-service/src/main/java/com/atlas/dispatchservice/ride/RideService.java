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

    public RideService(MatchingService matchingService, OsrmClient osrmClient, PricingClient pricingClient,
                        TripClient tripClient, DriverAssignmentService driverAssignmentService) {
        this.matchingService = matchingService;
        this.osrmClient = osrmClient;
        this.pricingClient = pricingClient;
        this.tripClient = tripClient;
        this.driverAssignmentService = driverAssignmentService;
    }

    public RideResponse requestRide(RideRequest request) {
        Coordinate pickup = new Coordinate(request.pickup().lat(), request.pickup().lng());
        Coordinate drop = new Coordinate(request.drop().lat(), request.drop().lng());

        List<CandidateScore> ranked;
        try {
            ranked = matchingService.rankCandidates(pickup);
        } catch (Exception e) {
            log.error("Driver DB unreachable while ranking candidates for rider {}: {}", request.riderId(), e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        if (ranked.isEmpty()) {
            return noMatch(request.riderId(), pickup, drop);
        }

        CandidateScore winner = ranked.get(0);
        DistanceResult tripDistance = osrmClient.distanceAndDuration(pickup, drop);

        double price;
        try {
            price = pricingClient.getQuote(pickup, drop, tripDistance);
        } catch (PricingClient.PricingUnavailableException e) {
            log.error("Pricing unreachable for rider {}: {}", request.riderId(), e.toString());
            return RideResponse.systemError("Unable to price this ride, try again shortly");
        }

        try {
            driverAssignmentService.assignTrip(winner.driverId(), drop, tripDistance.durationMinutes());
        } catch (Exception e) {
            log.error("Driver DB unreachable while assigning driver {} for rider {}: {}",
                    winner.driverId(), request.riderId(), e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }

        String tripId;
        try {
            tripId = tripClient.recordMatchedTrip(request.riderId(), winner.driverId(), pickup, drop, price, tripDistance);
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

        return RideResponse.matched(tripId, winner.driverId(), winner.effectiveStatus().name(),
                request.pickup(), request.drop(), price, (int) Math.round(winner.totalTimeToPickupMinutes()), etaNote);
    }

    private RideResponse noMatch(String riderId, Coordinate pickup, Coordinate drop) {
        try {
            tripClient.recordFailedNoMatch(riderId, pickup, drop);
        } catch (TripClient.TripUnavailableException e) {
            log.error("Trip unreachable while logging FAILED_NO_MATCH for rider {}: {}", riderId, e.toString());
            return RideResponse.systemError("Unable to process this ride, try again shortly");
        }
        return RideResponse.failedNoMatch("No available or soon-to-be-available drivers found");
    }

    public CancelResponse cancelTrip(String tripId) {
        String driverId = tripClient.cancelTrip(tripId);
        if (driverId != null && !driverId.isBlank()) {
            driverAssignmentService.freeDriverForTrip(driverId);
        }
        return new CancelResponse("CANCELLED", tripId);
    }
}
