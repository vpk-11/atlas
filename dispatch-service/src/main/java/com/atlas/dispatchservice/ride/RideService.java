package com.atlas.dispatchservice.ride;

import org.springframework.stereotype.Service;

@Service
public class RideService {

    public RideResponse requestRide(RideRequest request) {
        return RideResponse.failedNoMatch("matching not implemented yet");
    }

    public CancelResponse cancelTrip(String tripId) {
        return new CancelResponse("CANCELLED", tripId);
    }
}
