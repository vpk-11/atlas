package com.atlas.dispatchservice.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RoadDistanceEstimatorTest {

    @Test
    void sameCoordinateHasZeroDistanceAndTime() {
        assertThat(RoadDistanceEstimator.estimateRoadDistanceKm(37.77, -122.42, 37.77, -122.42)).isZero();
        assertThat(RoadDistanceEstimator.estimateMinutes(37.77, -122.42, 37.77, -122.42)).isZero();
    }

    @Test
    void appliesCircuityFactorOnTopOfHaversine() {
        double straightLineKm = GeoMath.haversineKm(37.77, -122.42, 37.80, -122.45);
        double roadKm = RoadDistanceEstimator.estimateRoadDistanceKm(37.77, -122.42, 37.80, -122.45);

        assertThat(roadKm).isCloseTo(straightLineKm * RoadDistanceEstimator.CIRCUITY_FACTOR, within(1e-9));
    }

    @Test
    void convertsDistanceToMinutesAtAssumedAverageSpeed() {
        // 1 mile straight line roughly north, circuity-scaled, at 30mph should take a few minutes
        double minutes = RoadDistanceEstimator.estimateMinutes(37.77, -122.42, 37.7844, -122.42);
        assertThat(minutes).isGreaterThan(0).isLessThan(15);
    }
}
