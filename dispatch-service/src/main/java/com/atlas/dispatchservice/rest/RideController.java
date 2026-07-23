package com.atlas.dispatchservice.rest;

import com.atlas.dispatchservice.ride.CancelResponse;
import com.atlas.dispatchservice.ride.RideRequest;
import com.atlas.dispatchservice.ride.RideResponse;
import com.atlas.dispatchservice.ride.RideService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RideController {

    private final RideService rideService;

    public RideController(RideService rideService) {
        this.rideService = rideService;
    }

    @PostMapping("/rides")
    public RideResponse requestRide(@Valid @RequestBody RideRequest request) {
        return rideService.requestRide(request);
    }

    @PostMapping("/trips/{tripId}/cancel")
    public CancelResponse cancelTrip(@PathVariable String tripId) {
        return rideService.cancelTrip(tripId);
    }
}
