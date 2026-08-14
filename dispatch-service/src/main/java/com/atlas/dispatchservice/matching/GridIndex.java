package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.BoundingBox;
import com.atlas.dispatchservice.driver.Driver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live grid-bucket index over driver current_location. Fixed-size square cells
 * over the simulated metro area; rebuilt wholesale on a schedule (see
 * GridIndexRefresher) rather than moved per-heartbeat, so each Dispatch replica
 * independently converges on the same view within one refresh interval instead
 * of diverging on in-memory state only one pod ever saw.
 *
 * Narrows broadly ("which cells is this pickup near"); MatchingService still
 * runs a precise quad-tree ranking on the narrowed set.
 */
@Component
public class GridIndex {

    // ponytail: fixed cell count over a fixed bounding box, not a general geo grid.
    // ~999 seeded drivers / 400 cells => a 3x3 neighbor query lands near
    // MIN_CANDIDATES candidates. Revisit if driver count or metro area size
    // changes by an order of magnitude.
    private static final int CELLS_PER_SIDE = 20;
    // Matches MatchingService.CANDIDATE_POOL_SIZE. Sparse-cell fallback target:
    // widen the neighbor radius until this many candidates are found (or the
    // whole grid is covered), instead of silently returning a starved set near
    // the map edge or in a low-density area.
    private static final int MIN_CANDIDATES = 20;
    private static final BoundingBox BOUNDS = BoundingBox.SAN_FRANCISCO;

    private volatile Map<Long, List<QuadTree.IndexedPoint>> cells = Map.of();

    public void rebuild(List<Driver> drivers) {
        Map<Long, List<QuadTree.IndexedPoint>> fresh = new HashMap<>();
        for (Driver driver : drivers) {
            long key = cellKey(row(driver.getCurrentLat()), col(driver.getCurrentLng()));
            fresh.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new QuadTree.IndexedPoint(driver.getDriverId(), driver.getCurrentLat(), driver.getCurrentLng()));
        }
        this.cells = fresh;
    }

    /**
     * Starts at the immediate 3x3 neighbor block and widens the radius one ring
     * at a time until at least MIN_CANDIDATES are found or the whole grid has
     * been covered. Keeps candidate retrieval broad-but-narrow in the normal
     * case, while a sparse area (map edge, low driver density) still returns a
     * usable set instead of starving the scorer.
     */
    public List<QuadTree.IndexedPoint> candidatesNear(double lat, double lng) {
        int centerRow = row(lat);
        int centerCol = col(lng);
        Map<Long, List<QuadTree.IndexedPoint>> snapshot = cells;

        List<QuadTree.IndexedPoint> result = new ArrayList<>();
        for (int radius = 1; radius <= CELLS_PER_SIDE; radius++) {
            result.clear();
            for (int dr = -radius; dr <= radius; dr++) {
                for (int dc = -radius; dc <= radius; dc++) {
                    List<QuadTree.IndexedPoint> bucket = snapshot.get(cellKey(centerRow + dr, centerCol + dc));
                    if (bucket != null) {
                        result.addAll(bucket);
                    }
                }
            }
            if (result.size() >= MIN_CANDIDATES) {
                break;
            }
        }
        return result;
    }

    private int row(double lat) {
        double clamped = Math.max(BOUNDS.minLat(), Math.min(BOUNDS.maxLat(), lat));
        double fraction = (clamped - BOUNDS.minLat()) / (BOUNDS.maxLat() - BOUNDS.minLat());
        return Math.min(CELLS_PER_SIDE - 1, (int) (fraction * CELLS_PER_SIDE));
    }

    private int col(double lng) {
        double clamped = Math.max(BOUNDS.minLng(), Math.min(BOUNDS.maxLng(), lng));
        double fraction = (clamped - BOUNDS.minLng()) / (BOUNDS.maxLng() - BOUNDS.minLng());
        return Math.min(CELLS_PER_SIDE - 1, (int) (fraction * CELLS_PER_SIDE));
    }

    private long cellKey(int row, int col) {
        return ((long) row << 32) | (col & 0xFFFFFFFFL);
    }
}
