package com.atlas.tripservice.trip;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class TripRecordService {

    private final TripRepository tripRepository;

    public TripRecordService(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    public record RecordResult(Trip trip, boolean replayed) {
    }

    // Not @Transactional: a unique-key violation inside an outer transaction would mark it
    // rollback-only and make the re-read below fail. save() runs in its own transaction.
    public RecordResult recordTrip(String riderId, String driverId, double pickupLat, double pickupLng,
                                    double dropLat, double dropLng, double price, double distanceKm,
                                    double durationMinutes, DistanceSource distanceSource, TripStatus status,
                                    String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        if (key != null) {
            Optional<Trip> existing = tripRepository.findByIdempotencyKey(key);
            if (existing.isPresent()) {
                return new RecordResult(existing.get(), true);
            }
        }
        Trip trip = new Trip(riderId, blankToNull(driverId), pickupLat, pickupLng, dropLat, dropLng,
                price, distanceKm, durationMinutes, distanceSource, status);
        trip.setIdempotencyKey(key);
        try {
            tripRepository.save(trip);
        } catch (DataIntegrityViolationException e) {
            // Concurrent first write with the same key won the unique constraint.
            if (key == null) {
                throw e;
            }
            return new RecordResult(tripRepository.findByIdempotencyKey(key).orElseThrow(() -> e), true);
        }
        return new RecordResult(trip, false);
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
