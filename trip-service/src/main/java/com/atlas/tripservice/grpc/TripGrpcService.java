package com.atlas.tripservice.grpc;

import com.atlas.tripservice.grpc.trip.CancelTripRequest;
import com.atlas.tripservice.grpc.trip.CancelTripResponse;
import com.atlas.tripservice.grpc.trip.RecordTripRequest;
import com.atlas.tripservice.grpc.trip.RecordTripResponse;
import com.atlas.tripservice.grpc.trip.TripServiceGrpc;
import com.atlas.tripservice.trip.DistanceSource;
import com.atlas.tripservice.trip.Trip;
import com.atlas.tripservice.trip.TripNotFoundException;
import com.atlas.tripservice.trip.TripRecordService;
import com.atlas.tripservice.trip.TripStatus;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;

@GrpcService
public class TripGrpcService extends TripServiceGrpc.TripServiceImplBase {

    private final TripRecordService tripRecordService;

    public TripGrpcService(TripRecordService tripRecordService) {
        this.tripRecordService = tripRecordService;
    }

    @Override
    public void recordTrip(RecordTripRequest request, StreamObserver<RecordTripResponse> responseObserver) {
        String tripId = tripRecordService.recordTrip(
                request.getRiderId(),
                request.getDriverId(),
                request.getPickup().getLat(),
                request.getPickup().getLng(),
                request.getDrop().getLat(),
                request.getDrop().getLng(),
                request.getPrice(),
                request.getDistanceKm(),
                request.getDurationMinutes(),
                toDomainDistanceSource(request.getDistanceSource()),
                toDomainStatus(request.getStatus()));

        responseObserver.onNext(RecordTripResponse.newBuilder().setTripId(tripId).build());
        responseObserver.onCompleted();
    }

    @Override
    public void cancelTrip(CancelTripRequest request, StreamObserver<CancelTripResponse> responseObserver) {
        try {
            Trip trip = tripRecordService.cancelTrip(request.getTripId());
            responseObserver.onNext(CancelTripResponse.newBuilder()
                    .setTripId(trip.getTripId())
                    .setStatus(com.atlas.tripservice.grpc.trip.TripStatus.CANCELLED)
                    .setDriverId(trip.getDriverId() == null ? "" : trip.getDriverId())
                    .build());
            responseObserver.onCompleted();
        } catch (TripNotFoundException e) {
            responseObserver.onError(Status.NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    private TripStatus toDomainStatus(com.atlas.tripservice.grpc.trip.TripStatus status) {
        return switch (status) {
            case MATCHED -> TripStatus.MATCHED;
            case FAILED_NO_MATCH -> TripStatus.FAILED_NO_MATCH;
            case CANCELLED -> TripStatus.CANCELLED;
            default -> throw new IllegalArgumentException("Unsupported trip status on record: " + status);
        };
    }

    private DistanceSource toDomainDistanceSource(com.atlas.tripservice.grpc.common.DistanceSource source) {
        return switch (source) {
            case OSRM -> DistanceSource.OSRM;
            case FALLBACK -> DistanceSource.FALLBACK;
            default -> null;
        };
    }
}
