package com.atlas.tripservice.grpc;

import com.atlas.tripservice.grpc.common.Coordinate;
import com.atlas.tripservice.grpc.trip.CancelTripRequest;
import com.atlas.tripservice.grpc.trip.CancelTripResponse;
import com.atlas.tripservice.grpc.trip.RecordTripRequest;
import com.atlas.tripservice.grpc.trip.RecordTripResponse;
import com.atlas.tripservice.grpc.trip.UpdateTripStatusRequest;
import com.atlas.tripservice.grpc.trip.UpdateTripStatusResponse;
import com.atlas.tripservice.trip.Trip;
import com.atlas.tripservice.trip.TripNotFoundException;
import com.atlas.tripservice.trip.TripRecordService;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripGrpcServiceTest {

    @Mock
    private TripRecordService tripRecordService;
    @Mock
    private StreamObserver<RecordTripResponse> recordObserver;
    @Mock
    private StreamObserver<UpdateTripStatusResponse> updateObserver;
    @Mock
    private StreamObserver<CancelTripResponse> cancelObserver;

    private TripGrpcService tripGrpcService;

    @BeforeEach
    void setUp() {
        tripGrpcService = new TripGrpcService(tripRecordService);
    }

    @Test
    void recordTripDelegatesToServiceAndReturnsTripId() {
        when(tripRecordService.recordTrip(eq("R-001"), eq("D-042"), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), any(), any()))
                .thenReturn("T-00000001");

        RecordTripRequest request = RecordTripRequest.newBuilder()
                .setRiderId("R-001")
                .setDriverId("D-042")
                .setPickup(Coordinate.newBuilder().setLat(1.0).setLng(2.0).build())
                .setDrop(Coordinate.newBuilder().setLat(3.0).setLng(4.0).build())
                .setPrice(14.5)
                .setDistanceKm(5.0)
                .setDurationMinutes(8.0)
                .setStatus(com.atlas.tripservice.grpc.trip.TripStatus.MATCHED)
                .setDistanceSource(com.atlas.tripservice.grpc.common.DistanceSource.OSRM)
                .build();

        tripGrpcService.recordTrip(request, recordObserver);

        ArgumentCaptor<RecordTripResponse> captor = ArgumentCaptor.forClass(RecordTripResponse.class);
        verify(recordObserver).onNext(captor.capture());
        verify(recordObserver).onCompleted();
        assertThat(captor.getValue().getTripId()).isEqualTo("T-00000001");
    }

    @Test
    void updateTripStatusDelegatesToServiceAndReturnsTripId() {
        Trip trip = new Trip("R-001", "D-042", 1.0, 2.0, 3.0, 4.0, 14.5, 5.0, 8.0,
                com.atlas.tripservice.trip.DistanceSource.OSRM, com.atlas.tripservice.trip.TripStatus.MATCHED);
        ReflectionTestUtils.setField(trip, "id", 9L);
        ReflectionTestUtils.invokeMethod(trip, "assignTripId");
        when(tripRecordService.updateTripStatus(eq("T-00000009"), eq("D-042"), anyDouble(), anyDouble(),
                anyDouble(), any(), any())).thenReturn(trip);

        UpdateTripStatusRequest request = UpdateTripStatusRequest.newBuilder()
                .setTripId("T-00000009")
                .setDriverId("D-042")
                .setPrice(14.5)
                .setDistanceKm(5.0)
                .setDurationMinutes(8.0)
                .setStatus(com.atlas.tripservice.grpc.trip.TripStatus.MATCHED)
                .setDistanceSource(com.atlas.tripservice.grpc.common.DistanceSource.OSRM)
                .build();

        tripGrpcService.updateTripStatus(request, updateObserver);

        ArgumentCaptor<UpdateTripStatusResponse> captor = ArgumentCaptor.forClass(UpdateTripStatusResponse.class);
        verify(updateObserver).onNext(captor.capture());
        verify(updateObserver).onCompleted();
        assertThat(captor.getValue().getTripId()).isEqualTo("T-00000009");
    }

    @Test
    void updateTripStatusSendsNotFoundErrorWhenTripDoesNotExist() {
        when(tripRecordService.updateTripStatus(eq("T-999"), any(), anyDouble(), anyDouble(), anyDouble(),
                any(), any())).thenThrow(new TripNotFoundException("T-999"));

        tripGrpcService.updateTripStatus(
                UpdateTripStatusRequest.newBuilder().setTripId("T-999")
                        .setStatus(com.atlas.tripservice.grpc.trip.TripStatus.SYSTEM_ERROR).build(),
                updateObserver);

        verify(updateObserver).onError(any());
        verify(updateObserver, never()).onNext(any());
    }

    @Test
    void cancelTripReturnsDriverIdSoDispatchCanFreeTheDriver() {
        Trip trip = new Trip("R-001", "D-005", 1.0, 2.0, 3.0, 4.0, 10.0, 5.0, 8.0,
                com.atlas.tripservice.trip.DistanceSource.OSRM, com.atlas.tripservice.trip.TripStatus.CANCELLED);
        ReflectionTestUtils.setField(trip, "id", 2L);
        ReflectionTestUtils.invokeMethod(trip, "assignTripId");
        when(tripRecordService.cancelTrip("T-00000002")).thenReturn(trip);

        tripGrpcService.cancelTrip(
                CancelTripRequest.newBuilder().setTripId("T-00000002").build(), cancelObserver);

        ArgumentCaptor<CancelTripResponse> captor = ArgumentCaptor.forClass(CancelTripResponse.class);
        verify(cancelObserver).onNext(captor.capture());
        verify(cancelObserver).onCompleted();
        assertThat(captor.getValue().getDriverId()).isEqualTo("D-005");
        assertThat(captor.getValue().getStatus()).isEqualTo(com.atlas.tripservice.grpc.trip.TripStatus.CANCELLED);
    }

    @Test
    void cancelTripSendsNotFoundErrorWhenTripDoesNotExist() {
        when(tripRecordService.cancelTrip("T-999")).thenThrow(new TripNotFoundException("T-999"));

        tripGrpcService.cancelTrip(
                CancelTripRequest.newBuilder().setTripId("T-999").build(), cancelObserver);

        verify(cancelObserver).onError(any());
        verify(cancelObserver, never()).onNext(any());
    }
}
