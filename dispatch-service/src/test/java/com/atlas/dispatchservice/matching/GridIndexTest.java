package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.driver.Driver;
import com.atlas.dispatchservice.driver.DriverStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GridIndexTest {

    @Test
    void densAreaStaysLocalAndExcludesFarDrivers() {
        GridIndex gridIndex = new GridIndex();
        List<Driver> drivers = new ArrayList<>();
        // 25 drivers clustered near the query point: enough to satisfy
        // MIN_CANDIDATES from the immediate 3x3 block alone, no widening needed.
        for (int i = 0; i < 25; i++) {
            drivers.add(new Driver("D-near-%02d".formatted(i), 37.7750, -122.4195, DriverStatus.AVAILABLE));
        }
        Driver farCorner = new Driver("D-far", 37.70, -122.51, DriverStatus.AVAILABLE); // opposite corner
        drivers.add(farCorner);

        gridIndex.rebuild(drivers);

        List<QuadTree.IndexedPoint> nearby = gridIndex.candidatesNear(37.7749, -122.4194);

        assertThat(nearby).extracting(QuadTree.IndexedPoint::id).doesNotContain("D-far");
    }

    @Test
    void sparseAreaWidensInsteadOfStarvingCandidates() {
        GridIndex gridIndex = new GridIndex();
        Driver near = new Driver("D-001", 37.7750, -122.4195, DriverStatus.AVAILABLE);
        Driver farCorner = new Driver("D-002", 37.70, -122.51, DriverStatus.AVAILABLE);

        gridIndex.rebuild(List.of(near, farCorner));

        // Only 2 drivers total, below MIN_CANDIDATES: the 3x3 block alone would
        // starve the scorer, so the fallback must widen until both are found.
        List<QuadTree.IndexedPoint> nearby = gridIndex.candidatesNear(37.7749, -122.4194);

        assertThat(nearby).extracting(QuadTree.IndexedPoint::id).containsExactlyInAnyOrder("D-001", "D-002");
    }

    @Test
    void rebuildReplacesPreviousState() {
        GridIndex gridIndex = new GridIndex();
        gridIndex.rebuild(List.of(new Driver("D-001", 37.7750, -122.4195, DriverStatus.AVAILABLE)));
        gridIndex.rebuild(List.of(new Driver("D-002", 37.7750, -122.4195, DriverStatus.AVAILABLE)));

        List<QuadTree.IndexedPoint> nearby = gridIndex.candidatesNear(37.7749, -122.4194);

        assertThat(nearby).extracting(QuadTree.IndexedPoint::id).containsExactly("D-002");
    }
}
