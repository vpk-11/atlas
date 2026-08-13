package com.atlas.tripservice.trip;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripRecordService {

    private final TripRepository tripRepository;

    public TripRecordService(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Transactional
    public String recordTrip(String riderId, String driverId, double pickupLat, double pickupLng,
                              double dropLat, double dropLng, double price, double distanceKm,
                              double durationMinutes, DistanceSource distanceSource, TripStatus status) {
        Trip trip = new Trip(riderId, blankToNull(driverId), pickupLat, pickupLng, dropLat, dropLng,
                price, distanceKm, durationMinutes, distanceSource, status);
        tripRepository.save(trip);
        return trip.getTripId();
    }

    @Transactional
    public Trip updateTripStatus(String tripId, String driverId, double price, double distanceKm,
                                  double durationMinutes, DistanceSource distanceSource, TripStatus status) {
        Trip trip = tripRepository.findByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        trip.setDriverId(blankToNull(driverId));
        trip.setPrice(price);
        trip.setDistanceKm(distanceKm);
        trip.setDurationMinutes(durationMinutes);
        trip.setDistanceSource(distanceSource);
        trip.setStatus(status);
        return trip;
    }

    @Transactional
    public Trip cancelTrip(String tripId) {
        Trip trip = tripRepository.findByTripId(tripId)
                .orElseThrow(() -> new TripNotFoundException(tripId));
        trip.setStatus(TripStatus.CANCELLED);
        return trip;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
