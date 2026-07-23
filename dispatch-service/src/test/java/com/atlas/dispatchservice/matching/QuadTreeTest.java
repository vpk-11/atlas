package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuadTreeTest {

    @Test
    void kNearestReturnsClosestPointsInAscendingDistanceOrder() {
        QuadTree tree = new QuadTree(BoundingBox.SAN_FRANCISCO);
        tree.insert("near", 37.7749, -122.4194);
        tree.insert("mid", 37.79, -122.40);
        tree.insert("far", 37.83, -122.36);

        List<QuadTree.IndexedPoint> nearest = tree.kNearest(37.7749, -122.4194, 2);

        assertThat(nearest).hasSize(2);
        assertThat(nearest.get(0).id()).isEqualTo("near");
        assertThat(nearest.get(1).id()).isEqualTo("mid");
    }

    @Test
    void kNearestCapsAtAvailablePointCount() {
        QuadTree tree = new QuadTree(BoundingBox.SAN_FRANCISCO);
        tree.insert("only", 37.75, -122.43);

        List<QuadTree.IndexedPoint> nearest = tree.kNearest(37.75, -122.43, 5);

        assertThat(nearest).hasSize(1);
    }

    @Test
    void handlesManyPointsRequiringSubdivision() {
        QuadTree tree = new QuadTree(BoundingBox.SAN_FRANCISCO);
        for (int i = 0; i < 500; i++) {
            double lat = 37.70 + (i % 50) * 0.001;
            double lng = -122.51 + (i % 50) * 0.001;
            tree.insert("D-" + i, lat, lng);
        }

        List<QuadTree.IndexedPoint> nearest = tree.kNearest(37.75, -122.45, 10);

        assertThat(nearest).hasSize(10);
    }
}
