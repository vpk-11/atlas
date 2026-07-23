package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.RoadDistanceEstimator;
import com.atlas.dispatchservice.driver.Driver;
import com.atlas.dispatchservice.driver.DriverRepository;
import com.atlas.dispatchservice.driver.DriverStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class MatchingService {

    private static final Logger log = LoggerFactory.getLogger(MatchingService.class);
    private static final int CANDIDATE_POOL_SIZE = 20;

    private final QuadTreeIndex quadTreeIndex;
    private final DriverRepository driverRepository;

    public MatchingService(QuadTreeIndex quadTreeIndex, DriverRepository driverRepository) {
        this.quadTreeIndex = quadTreeIndex;
        this.driverRepository = driverRepository;
    }

    /**
     * Quad-tree candidate retrieval narrowed to raw nearest drivers, then lookahead
     * scoring on that narrowed set. Returns the full ranked list, best candidate first.
     */
    public List<CandidateScore> rankCandidates(Coordinate pickup) {
        List<QuadTree.IndexedPoint> nearest = quadTreeIndex.nearest(pickup.lat(), pickup.lng(), CANDIDATE_POOL_SIZE);
        Instant now = Instant.now();

        List<CandidateScore> scored = new ArrayList<>();
        for (QuadTree.IndexedPoint point : nearest) {
            driverRepository.findById(point.id()).ifPresent(driver -> {
                if (driver.getStatus() == DriverStatus.OFFLINE) {
                    return;
                }
                scored.add(score(driver, pickup, now));
            });
        }

        scored.sort(Comparator.comparingDouble(CandidateScore::totalTimeToPickupMinutes));
        log.info("Ranked {} candidates for pickup ({}, {}): {}", scored.size(), pickup.lat(), pickup.lng(), scored);
        return scored;
    }

    private CandidateScore score(Driver driver, Coordinate pickup, Instant now) {
        boolean expiredOnTrip = driver.getStatus() == DriverStatus.ON_TRIP
                && driver.getEstimatedFreeAt() != null
                && driver.getEstimatedFreeAt().isBefore(now);

        if (driver.getStatus() == DriverStatus.AVAILABLE || expiredOnTrip) {
            double lat = expiredOnTrip && driver.getDestinationLat() != null
                    ? driver.getDestinationLat() : driver.getCurrentLat();
            double lng = expiredOnTrip && driver.getDestinationLng() != null
                    ? driver.getDestinationLng() : driver.getCurrentLng();
            double timeToPickup = RoadDistanceEstimator.estimateMinutes(lat, lng, pickup.lat(), pickup.lng());
            return new CandidateScore(driver.getDriverId(), DriverStatus.AVAILABLE, timeToPickup, null);
        }

        // still genuinely on trip: time remaining until free, plus destination -> pickup leg
        double remainingMinutes = Math.max(0, java.time.Duration.between(now, driver.getEstimatedFreeAt()).toSeconds() / 60.0);
        double destLat = driver.getDestinationLat() != null ? driver.getDestinationLat() : driver.getCurrentLat();
        double destLng = driver.getDestinationLng() != null ? driver.getDestinationLng() : driver.getCurrentLng();
        double legMinutes = RoadDistanceEstimator.estimateMinutes(destLat, destLng, pickup.lat(), pickup.lng());
        return new CandidateScore(driver.getDriverId(), DriverStatus.ON_TRIP, remainingMinutes + legMinutes, remainingMinutes);
    }
}
