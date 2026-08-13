package com.atlas.dispatchservice.trip;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.domain.DistanceSource;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class TripClient {

    private static final long DEADLINE_MS = 2000; // [ASSUMED] fail fast rather than hang on a wedged channel

    @GrpcClient("trip-service")
    private com.atlas.dispatchservice.grpc.trip.TripServiceGrpc.TripServiceBlockingStub tripStub;

    /**
     * Writes the initial REQUESTED row before any matching/pricing work happens, so a
     * mid-match crash still leaves a durable record of the attempt (see root CLAUDE.md
     * Phase 4.1 decisions). Returns the trip_id that all later calls for this ride must
     * update, not replace.
     */
    public String recordRequested(String riderId, Coordinate pickup, Coordinate drop) {
        var request = com.atlas.dispatchservice.grpc.trip.RecordTripRequest.newBuilder()
                .setRiderId(riderId)
                .setDriverId("")
                .setPickup(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(pickup.lat()).setLng(pickup.lng()).build())
                .setDrop(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(drop.lat()).setLng(drop.lng()).build())
                .setPrice(0.0)
                .setDistanceKm(0.0)
                .setDurationMinutes(0.0)
                .setStatus(com.atlas.dispatchservice.grpc.trip.TripStatus.REQUESTED)
                .build();

        try {
            return tripStub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS).recordTrip(request).getTripId();
        } catch (Exception e) {
            throw new TripUnavailableException(e);
        }
    }

    public void finalizeMatchedTrip(String tripId, String driverId, double price, DistanceResult distance) {
        updateStatus(tripId, driverId, price, distance, com.atlas.dispatchservice.grpc.trip.TripStatus.MATCHED);
    }

    public void finalizeFailedNoMatch(String tripId) {
        updateStatus(tripId, "", 0.0, new DistanceResult(0.0, 0.0, DistanceSource.FALLBACK),
                com.atlas.dispatchservice.grpc.trip.TripStatus.FAILED_NO_MATCH);
    }

    public void finalizeSystemError(String tripId) {
        updateStatus(tripId, "", 0.0, new DistanceResult(0.0, 0.0, DistanceSource.FALLBACK),
                com.atlas.dispatchservice.grpc.trip.TripStatus.SYSTEM_ERROR);
    }

    private void updateStatus(String tripId, String driverId, double price, DistanceResult distance,
                               com.atlas.dispatchservice.grpc.trip.TripStatus status) {
        var request = com.atlas.dispatchservice.grpc.trip.UpdateTripStatusRequest.newBuilder()
                .setTripId(tripId)
                .setDriverId(driverId)
                .setPrice(price)
                .setDistanceKm(distance.distanceKm())
                .setDurationMinutes(distance.durationMinutes())
                .setStatus(status)
                .setDistanceSource(distance.source() == DistanceSource.OSRM
                        ? com.atlas.dispatchservice.grpc.common.DistanceSource.OSRM
                        : com.atlas.dispatchservice.grpc.common.DistanceSource.FALLBACK)
                .build();

        try {
            tripStub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS).updateTripStatus(request);
        } catch (Exception e) {
            throw new TripUnavailableException(e);
        }
    }

    public String cancelTrip(String tripId) {
        var request = com.atlas.dispatchservice.grpc.trip.CancelTripRequest.newBuilder()
                .setTripId(tripId)
                .build();
        try {
            return tripStub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS).cancelTrip(request).getDriverId();
        } catch (Exception e) {
            throw new TripUnavailableException(e);
        }
    }

    public static class TripUnavailableException extends RuntimeException {
        public TripUnavailableException(Throwable cause) {
            super("Trip service unreachable", cause);
        }
    }
}
