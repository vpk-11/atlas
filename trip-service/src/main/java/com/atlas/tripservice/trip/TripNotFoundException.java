package com.atlas.tripservice.trip;

public class TripNotFoundException extends RuntimeException {
    public TripNotFoundException(String tripId) {
        super("Trip not found: " + tripId);
    }
}
