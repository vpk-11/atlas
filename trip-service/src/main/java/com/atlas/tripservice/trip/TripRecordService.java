package com.atlas.tripservice.trip;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TripRecordService {

    private final TripRepository tripRepository;
    private final long leaseSeconds;

    public TripRecordService(TripRepository tripRepository,
                             @Value("${trip.claim.lease-seconds}") long leaseSeconds) {
        this.tripRepository = tripRepository;
        this.leaseSeconds = leaseSeconds;
    }

    /**
     * resumeGranted is only meaningful when replayed: true means this caller took over an unsettled
     * row and may run the match; false means the original request may still be running.
     */
    public record RecordResult(Trip trip, boolean replayed, boolean resumeGranted, String claimToken) {
    }

    /** applied is false when the write was dropped (row settled, or the caller's claim token is stale). */
    public record UpdateResult(String tripId, boolean applied, TripStatus currentStatus) {
    }

    // Not @Transactional: a unique-key violation inside an outer transaction would mark it
    // rollback-only and make the re-read below fail. save() runs in its own transaction.
    public RecordResult recordTrip(String riderId, String driverId, double pickupLat, double pickupLng,
                                    double dropLat, double dropLng, double price, double distanceKm,
                                    double durationMinutes, DistanceSource distanceSource, TripStatus status,
                                    String idempotencyKey) {
        String key = blankToNull(idempotencyKey);
        // Insert first: the common path has no replay, so skip a lookup per request. The
        // unique constraint decides who wins a duplicate key.
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
            Trip existing = tripRepository.findByRiderIdAndIdempotencyKey(riderId, key).orElseThrow(() -> e);
            String newToken = tryClaim(existing);
            return new RecordResult(existing, true, newToken != null, newToken);
        }
        return new RecordResult(trip, false, false, trip.getClaimToken());
    }

    /** Returns the new claim token if this caller took over the row, else null. */
    private String tryClaim(Trip existing) {
        if (existing.getStatus() != TripStatus.REQUESTED && existing.getStatus() != TripStatus.SYSTEM_ERROR) {
            return null;
        }
        Instant now = Instant.now();
        String newToken = UUID.randomUUID().toString();
        return tripRepository.claimForResume(existing.getTripId(), TripStatus.REQUESTED, TripStatus.SYSTEM_ERROR,
                now, newToken, now.minusSeconds(leaseSeconds)) == 1 ? newToken : null;
    }

    @Transactional
    public UpdateResult updateTripStatus(String tripId, String claimToken, String driverId, double price, double distanceKm,
                                    double durationMinutes, DistanceSource distanceSource, TripStatus status) {
        int updated = tripRepository.updateOutcome(tripId, claimToken, blankToNull(driverId), price, distanceKm,
                durationMinutes, distanceSource, status, TripStatus.REQUESTED, TripStatus.SYSTEM_ERROR);
        if (updated == 1) {
            return new UpdateResult(tripId, true, status);
        }
        // Dropped: the row is already settled (MATCHED, FAILED_NO_MATCH or CANCELLED) or another
        // caller holds the claim now. The caller learns the current status, e.g. a cancel that
        // landed mid-match.
        Trip current = tripRepository.findByTripId(tripId).orElseThrow(() -> new TripNotFoundException(tripId));
        return new UpdateResult(tripId, false, current.getStatus());
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
