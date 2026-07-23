package com.atlas.dispatchservice.ride;

public record RideResponse(
        String status,
        String tripId,
        String driverId,
        String driverStatus,
        CoordinateDto pickupLocation,
        CoordinateDto dropLocation,
        Double price,
        Integer estimatedPickupMinutes,
        String etaNote,
        String message
) {

    public static RideResponse matched(String tripId, String driverId, String driverStatus,
                                        CoordinateDto pickup, CoordinateDto drop, double price,
                                        int estimatedPickupMinutes, String etaNote) {
        return new RideResponse("MATCHED", tripId, driverId, driverStatus, pickup, drop, price,
                estimatedPickupMinutes, etaNote, null);
    }

    public static RideResponse failedNoMatch(String message) {
        return new RideResponse("FAILED_NO_MATCH", null, null, null, null, null, null, null, null, message);
    }

    public static RideResponse systemError(String message) {
        return new RideResponse("SYSTEM_ERROR", null, null, null, null, null, null, null, null, message);
    }
}
