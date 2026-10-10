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
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripRecordServiceTest {

    @Mock
    private TripRepository tripRepository;

    private TripRecordService tripRecordService;

    @BeforeEach
    void setUp() {
        tripRecordService = new TripRecordService(tripRepository, 10);
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
    void recordTripWithNewKeyInsertsWithoutALookup() {
        when(tripRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-1");

        assertThat(result.replayed()).isFalse();
        assertThat(result.trip().getIdempotencyKey()).isEqualTo("K-1");
        verify(tripRepository, org.mockito.Mockito.never()).findByRiderIdAndIdempotencyKey(any(), any());
    }

    @Test
    void recordTripKeyRaceLoserRereadsWinnersRow() {
        Trip winner = new Trip("R-001", null, 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0,
                null, TripStatus.REQUESTED);
        ReflectionTestUtils.setField(winner, "id", 6L);
        ReflectionTestUtils.invokeMethod(winner, "assignTripId");
        when(tripRepository.findByRiderIdAndIdempotencyKey("R-001", "K-2")).thenReturn(Optional.of(winner));
        when(tripRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-2");

        assertThat(result.replayed()).isTrue();
        assertThat(result.resumeGranted()).isFalse();
        assertThat(result.trip().getTripId()).isEqualTo("T-00000006");
    }

    @Test
    void replayOfUnsettledRowWithExpiredLeaseIsGrantedTheClaim() {
        Trip stale = new Trip("R-001", null, 1.0, 2.0, 3.0, 4.0, 0.0, 0.0, 0.0, null, TripStatus.REQUESTED);
        ReflectionTestUtils.setField(stale, "id", 8L);
        ReflectionTestUtils.invokeMethod(stale, "assignTripId");
        when(tripRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        when(tripRepository.findByRiderIdAndIdempotencyKey("R-001", "K-3")).thenReturn(Optional.of(stale));
        when(tripRepository.claimForResume(eq("T-00000008"), eq(TripStatus.REQUESTED), eq(TripStatus.SYSTEM_ERROR),
                any(), any(), any())).thenReturn(1);

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-3");

        assertThat(result.replayed()).isTrue();
        assertThat(result.resumeGranted()).isTrue();
        assertThat(result.claimToken()).isNotNull().isNotEqualTo(stale.getClaimToken());
    }

    @Test
    void replayOfSettledRowNeverTriesToClaim() {
        Trip matched = new Trip("R-001", "D-1", 1.0, 2.0, 3.0, 4.0, 9.0, 1.0, 1.0,
                DistanceSource.OSRM, TripStatus.MATCHED);
        ReflectionTestUtils.setField(matched, "id", 9L);
        ReflectionTestUtils.invokeMethod(matched, "assignTripId");
        when(tripRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup"));
        when(tripRepository.findByRiderIdAndIdempotencyKey("R-001", "K-4")).thenReturn(Optional.of(matched));

        TripRecordService.RecordResult result = tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, "K-4");

        assertThat(result.replayed()).isTrue();
        assertThat(result.resumeGranted()).isFalse();
        verify(tripRepository, org.mockito.Mockito.never()).claimForResume(any(), any(), any(), any(), any(), any());
    }

    @Test
    void duplicateInsertWithoutAKeyIsNotSwallowed() {
        when(tripRepository.save(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("boom"));

        assertThatThrownBy(() -> tripRecordService.recordTrip("R-001", "", 1.0, 2.0, 3.0, 4.0,
                0.0, 0.0, 0.0, null, TripStatus.REQUESTED, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void updateTripStatusIssuesOneUpdateAndReturnsTheTripId() {
        when(tripRepository.updateOutcome(eq("T-00000009"), eq("tok"), eq("D-042"), eq(14.5), eq(5.0), eq(8.0),
                eq(DistanceSource.OSRM), eq(TripStatus.MATCHED), eq(TripStatus.REQUESTED), eq(TripStatus.SYSTEM_ERROR)))
                .thenReturn(1);

        TripRecordService.UpdateResult result = tripRecordService.updateTripStatus("T-00000009", "tok", "D-042",
                14.5, 5.0, 8.0, DistanceSource.OSRM, TripStatus.MATCHED);

        assertThat(result.tripId()).isEqualTo("T-00000009");
        assertThat(result.applied()).isTrue();
        verify(tripRepository, org.mockito.Mockito.never()).save(any());
        verify(tripRepository, org.mockito.Mockito.never()).findByTripId(any());
    }

    @Test
    void updateTripStatusBlankDriverIdBecomesNull() {
        when(tripRepository.updateOutcome(any(), any(), any(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(1);

        tripRecordService.updateTripStatus("T-1", "tok", "", 0.0, 0.0, 0.0, DistanceSource.FALLBACK, TripStatus.SYSTEM_ERROR);

        verify(tripRepository).updateOutcome(eq("T-1"), eq("tok"), org.mockito.ArgumentMatchers.isNull(), anyDouble(),
                anyDouble(), anyDouble(), any(), any(), any(), any());
    }

    @Test
    void updateTripStatusOnAnAlreadySettledRowDropsTheLateWriteWithoutError() {
        Trip settled = new Trip("R-001", "D-1", 1.0, 2.0, 3.0, 4.0, 9.0, 1.0, 1.0,
                DistanceSource.OSRM, TripStatus.CANCELLED);
        when(tripRepository.updateOutcome(any(), any(), any(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(0);
        when(tripRepository.findByTripId("T-5")).thenReturn(Optional.of(settled));

        TripRecordService.UpdateResult result = tripRecordService.updateTripStatus("T-5", "tok", "D-1", 9.0, 1.0,
                1.0, DistanceSource.OSRM, TripStatus.MATCHED);

        assertThat(result.applied()).isFalse();
        assertThat(result.currentStatus()).isEqualTo(TripStatus.CANCELLED);
    }

    @Test
    void updateTripStatusThrowsWhenTripNotFound() {
        when(tripRepository.updateOutcome(any(), any(), any(), anyDouble(), anyDouble(), anyDouble(), any(), any(), any(), any()))
                .thenReturn(0);

        assertThatThrownBy(() -> tripRecordService.updateTripStatus("T-999", "tok", "D-001", 0.0, 0.0, 0.0,
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
