package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.BoundingBox;
import com.atlas.dispatchservice.driver.Driver;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Holds the in-memory quad-tree over driver current_location. Rebuilt explicitly
 * (not on a schedule) since driver locations are seeded once and never move in v1.
 */
@Component
public class QuadTreeIndex {

    private volatile QuadTree tree = new QuadTree(BoundingBox.SAN_FRANCISCO);

    public void rebuild(List<Driver> drivers) {
        QuadTree fresh = new QuadTree(BoundingBox.SAN_FRANCISCO);
        for (Driver driver : drivers) {
            fresh.insert(driver.getDriverId(), driver.getCurrentLat(), driver.getCurrentLng());
        }
        this.tree = fresh;
    }

    public List<QuadTree.IndexedPoint> nearest(double lat, double lng, int k) {
        return tree.kNearest(lat, lng, k);
    }
}
