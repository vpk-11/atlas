package com.atlas.tripservice.trip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRecordServiceTest {

    @Mock
    private TripRepository tripRepository;

    private TripRecordService tripRecordService;

    @BeforeEach
    void setUp() {
        tripRecordService = new TripRecordService(tripRepository);
    }

    @Test
    void recordTripAssignsGeneratedTripIdAndConvertsBlankDriverIdToNull() {
        when(tripRepository.save(any())).thenAnswer(invocation -> {
            Trip trip = invocation.getArgument(0);
            ReflectionTestUtils.setField(trip, "id", 7L);
            ReflectionTestUtils.invokeMethod(trip, "assignTripId");
            return trip;
        });

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, DistanceSource.FALLBACK, TripStatus.FAILED_NO_MATCH, null);

        assertThat(result.trip().getTripId()).isEqualTo("T-00000007");
        assertThat(result.replayed()).isFalse();

        ArgumentCaptor<Trip> captor = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(captor.capture());
        assertThat(captor.getValue().getDriverId()).isNull();
        assertThat(captor.getValue().getStatus()).isEqualTo(TripStatus.FAILED_NO_MATCH);
    }

    @Test
    void recordTripWithKnownKeyReturnsExistingRowWithoutInserting() {
        Trip existing = new Trip("R-001", null, 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0,
                null, TripStatus.REQUESTED);
        ReflectionTestUtils.setField(existing, "id", 5L);
        ReflectionTestUtils.invokeMethod(existing, "assignTripId");
        when(tripRepository.findByIdempotencyKey("K-1")).thenReturn(Optional.of(existing));

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.trip().getTripId()).isEqualTo("T-00000005");
        verify(tripRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void recordTripKeyRaceLoserRereadsWinnersRow() {
        Trip winner = new Trip("R-001", null, 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0,
                null, TripStatus.REQUESTED);
        ReflectionTestUtils.setField(winner, "id", 6L);
        ReflectionTestUtils.invokeMethod(winner, "assignTripId");
        when(tripRepository.findByIdempotencyKey("K-2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(tripRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-2");

        assertThat(result.replayed()).isTrue();
        assertThat(result.trip().getTripId()).isEqualTo("T-00000006");
    }

    @Test
    void updateTripStatusMutatesExistingRowInsteadOfCreatingANewOne() {
        Trip trip = new Trip("R-001", null, 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0,
                null, TripStatus.REQUESTED);
        ReflectionTestUtils.setField(trip, "id", 9L);
        ReflectionTestUtils.invokeMethod(trip, "assignTripId");
        when(tripRepository.findByTripId("T-00000009")).thenReturn(Optional.of(trip));

        Trip result = tripRecordService.updateTripStatus("T-00000009", "D-042", 14.5, 5.0, 8.0,
                DistanceSource.OSRM, TripStatus.MATCHED);

        assertThat(result.getStatus()).isEqualTo(TripStatus.MATCHED);
        assertThat(result.getDriverId()).isEqualTo("D-042");
        assertThat(result.getPrice()).isEqualTo(14.5);
        org.mockito.Mockito.verify(tripRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void updateTripStatusThrowsWhenTripNotFound() {
        when(tripRepository.findByTripId("T-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripRecordService.updateTripStatus("T-999", "D-001", 0.0, 0.0, 0.0,
                DistanceSource.FALLBACK, TripStatus.SYSTEM_ERROR))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void cancelTripSetsStatusCancelledAndReturnsTrip() {
        Trip trip = new Trip("R-001", "D-042", 1.0, 2.0, 3.0, 4.0, 10.0, 5.0, 8.0,
                DistanceSource.OSRM, TripStatus.MATCHED);
        ReflectionTestUtils.setField(trip, "id", 3L);
        ReflectionTestUtils.invokeMethod(trip, "assignTripId");
        when(tripRepository.findByTripId("T-00000003")).thenReturn(Optional.of(trip));

        Trip result = tripRecordService.cancelTrip("T-00000003");

        assertThat(result.getStatus()).isEqualTo(TripStatus.CANCELLED);
        assertThat(result.getDriverId()).isEqualTo("D-042");
    }

    @Test
    void cancelTripThrowsWhenTripNotFound() {
        when(tripRepository.findByTripId("T-999")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tripRecordService.cancelTrip("T-999"))
                .isInstanceOf(TripNotFoundException.class);
    }
}
