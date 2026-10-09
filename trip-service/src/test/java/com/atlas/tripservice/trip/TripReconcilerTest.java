package com.atlas.tripservice.trip;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripReconcilerTest {

    @Mock
    private TripRepository tripRepository;

    @Test
    void sweepFailsRequestedRowsOlderThanThresholdAndCountsThem() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TripReconciler reconciler = new TripReconciler(tripRepository, registry, 60);
        when(tripRepository.updateStatusWhereOlderThan(eq(TripStatus.REQUESTED), eq(TripStatus.SYSTEM_ERROR), any()))
                .thenReturn(3);

        int n = reconciler.sweep();

        assertThat(n).isEqualTo(3);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(tripRepository).updateStatusWhereOlderThan(eq(TripStatus.REQUESTED), eq(TripStatus.SYSTEM_ERROR),
                cutoff.capture());
        assertThat(Duration.between(cutoff.getValue(), Instant.now()).getSeconds()).isBetween(59L, 62L);
        assertThat(registry.counter("trips_reconciled_total").count()).isEqualTo(3.0);
    }

    @Test
    void sweepWithNothingStaleLeavesCounterAtZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TripReconciler reconciler = new TripReconciler(tripRepository, registry, 60);
        when(tripRepository.updateStatusWhereOlderThan(any(), any(), any())).thenReturn(0);

        assertThat(reconciler.sweep()).isZero();
        assertThat(registry.counter("trips_reconciled_total").count()).isZero();
    }
}
