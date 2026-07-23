package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.BoundingBox;
import com.atlas.dispatchservice.domain.GeoMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class QuadTree {

    public record IndexedPoint(String id, double lat, double lng) {
    }

    private static final int NODE_CAPACITY = 8;
    private static final int MAX_DEPTH = 12;

    private final Node root;

    public QuadTree(BoundingBox bounds) {
        this.root = new Node(bounds, 0);
    }

    public void insert(String id, double lat, double lng) {
        root.insert(new IndexedPoint(id, lat, lng));
    }

    /**
     * Expands search radius from the query point until at least k candidates are
     * found (or the tree is exhausted), then returns the true k nearest by distance.
     */
    public List<IndexedPoint> kNearest(double lat, double lng, int k) {
        double radiusKm = 2.0;
        List<IndexedPoint> found = new ArrayList<>();
        double maxPossibleRadiusKm = 40_000; // generous upper bound, half earth circumference
        while (radiusKm < maxPossibleRadiusKm) {
            found.clear();
            root.rangeSearch(lat, lng, radiusKm, found);
            if (found.size() >= k) {
                break;
            }
            radiusKm *= 2;
        }
        return found.stream()
                .sorted(Comparator.comparingDouble(p -> GeoMath.haversineKm(lat, lng, p.lat(), p.lng())))
                .limit(k)
                .toList();
    }

    private static final class Node {
        private final BoundingBox bounds;
        private final int depth;
        private final List<IndexedPoint> points = new ArrayList<>();
        private Node[] children;

        private Node(BoundingBox bounds, int depth) {
            this.bounds = bounds;
            this.depth = depth;
        }

        void insert(IndexedPoint point) {
            if (!bounds.contains(point.lat(), point.lng())) {
                return;
            }
            if (children != null) {
                for (Node child : children) {
                    if (child.bounds.contains(point.lat(), point.lng())) {
                        child.insert(point);
                        return;
                    }
                }
                return;
            }
            points.add(point);
            if (points.size() > NODE_CAPACITY && depth < MAX_DEPTH) {
                subdivide();
            }
        }

        private void subdivide() {
            double midLat = (bounds.minLat() + bounds.maxLat()) / 2;
            double midLng = (bounds.minLng() + bounds.maxLng()) / 2;
            children = new Node[]{
                    new Node(new BoundingBox(bounds.minLat(), midLat, bounds.minLng(), midLng), depth + 1),
                    new Node(new BoundingBox(bounds.minLat(), midLat, midLng, bounds.maxLng()), depth + 1),
                    new Node(new BoundingBox(midLat, bounds.maxLat(), bounds.minLng(), midLng), depth + 1),
                    new Node(new BoundingBox(midLat, bounds.maxLat(), midLng, bounds.maxLng()), depth + 1)
            };
            for (IndexedPoint existing : points) {
                for (Node child : children) {
                    if (child.bounds.contains(existing.lat(), existing.lng())) {
                        child.insert(existing);
                        break;
                    }
                }
            }
            points.clear();
        }

        void rangeSearch(double lat, double lng, double radiusKm, List<IndexedPoint> results) {
            if (!bounds.intersectsCircle(lat, lng, radiusKm)) {
                return;
            }
            if (children != null) {
                for (Node child : children) {
                    child.rangeSearch(lat, lng, radiusKm, results);
                }
                return;
            }
            for (IndexedPoint point : points) {
                if (GeoMath.haversineKm(lat, lng, point.lat(), point.lng()) <= radiusKm) {
                    results.add(point);
                }
            }
        }
    }
}
