package com.atlas.dispatchservice.domain;

public record BoundingBox(double minLat, double maxLat, double minLng, double maxLng) {

    public static final BoundingBox SAN_FRANCISCO = new BoundingBox(37.70, 37.83, -122.51, -122.36);

    public boolean contains(double lat, double lng) {
        return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
    }

    public boolean intersectsCircle(double lat, double lng, double radiusKm) {
        double closestLat = clamp(lat, minLat, maxLat);
        double closestLng = clamp(lng, minLng, maxLng);
        return GeoMath.haversineKm(lat, lng, closestLat, closestLng) <= radiusKm;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
