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

    public String recordMatchedTrip(String riderId, String driverId, Coordinate pickup, Coordinate drop,
                                     double price, DistanceResult distance) {
        return record(riderId, driverId, pickup, drop, price, distance,
                com.atlas.dispatchservice.grpc.trip.TripStatus.MATCHED);
    }

    public String recordFailedNoMatch(String riderId, Coordinate pickup, Coordinate drop) {
        return record(riderId, "", pickup, drop, 0.0,
                new DistanceResult(0.0, 0.0, DistanceSource.FALLBACK),
                com.atlas.dispatchservice.grpc.trip.TripStatus.FAILED_NO_MATCH);
    }

    public String recordSystemError(String riderId, Coordinate pickup, Coordinate drop) {
        return record(riderId, "", pickup, drop, 0.0,
                new DistanceResult(0.0, 0.0, DistanceSource.FALLBACK),
                com.atlas.dispatchservice.grpc.trip.TripStatus.SYSTEM_ERROR);
    }

    private String record(String riderId, String driverId, Coordinate pickup, Coordinate drop,
                           double price, DistanceResult distance,
                           com.atlas.dispatchservice.grpc.trip.TripStatus status) {
        var request = com.atlas.dispatchservice.grpc.trip.RecordTripRequest.newBuilder()
                .setRiderId(riderId)
                .setDriverId(driverId)
                .setPickup(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(pickup.lat()).setLng(pickup.lng()).build())
                .setDrop(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(drop.lat()).setLng(drop.lng()).build())
                .setPrice(price)
                .setDistanceKm(distance.distanceKm())
                .setDurationMinutes(distance.durationMinutes())
                .setStatus(status)
                .setDistanceSource(distance.source() == DistanceSource.OSRM
                        ? com.atlas.dispatchservice.grpc.common.DistanceSource.OSRM
                        : com.atlas.dispatchservice.grpc.common.DistanceSource.FALLBACK)
                .build();

        try {
            return tripStub.withDeadlineAfter(DEADLINE_MS, TimeUnit.MILLISECONDS).recordTrip(request).getTripId();
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
