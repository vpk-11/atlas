package com.atlas.dispatchservice.domain;

/**
 * Fallback road distance/time math: haversine straight-line distance scaled by a
 * circuity factor to approximate real road distance, then converted to time at a
 * flat average speed. Used whenever OSRM is unavailable, and always for driver-table
 * lookahead scoring (which never calls OSRM per candidate).
 */
public final class RoadDistanceEstimator {

    public static final double CIRCUITY_FACTOR = 1.3; // [ASSUMED]
    public static final double AVERAGE_SPEED_MPH = 30.0; // [ASSUMED]
    private static final double KM_PER_MILE = 1.60934;

    private RoadDistanceEstimator() {
    }

    public static double estimateRoadDistanceKm(double lat1, double lng1, double lat2, double lng2) {
        return GeoMath.haversineKm(lat1, lng1, lat2, lng2) * CIRCUITY_FACTOR;
    }

    public static double estimateMinutes(double lat1, double lng1, double lat2, double lng2) {
        double distanceKm = estimateRoadDistanceKm(lat1, lng1, lat2, lng2);
        double speedKmh = AVERAGE_SPEED_MPH * KM_PER_MILE;
        double hours = distanceKm / speedKmh;
        return hours * 60.0;
    }
}
