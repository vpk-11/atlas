package com.atlas.dispatchservice.ride;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.domain.DistanceSource;
import com.atlas.dispatchservice.driver.DriverAssignmentService;
import com.atlas.dispatchservice.driver.DriverStatus;
import com.atlas.dispatchservice.matching.CandidateScore;
import com.atlas.dispatchservice.matching.MatchingService;
import com.atlas.dispatchservice.osrm.OsrmClient;
import com.atlas.dispatchservice.pricing.PricingClient;
import com.atlas.dispatchservice.trip.TripClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RideServiceTest {

    @Mock
    private MatchingService matchingService;
    @Mock
    private OsrmClient osrmClient;
    @Mock
    private PricingClient pricingClient;
    @Mock
    private TripClient tripClient;
    @Mock
    private DriverAssignmentService driverAssignmentService;

    private RideService rideService;
    private SimpleMeterRegistry meterRegistry;

    private final RideRequest request = new RideRequest("R-001",
            new CoordinateDto(37.7749, -122.4194), new CoordinateDto(37.80, -122.41));

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        // Same-thread executor: the post-OSRM continuation runs synchronously
        // right here, same as the old blocking code did, so tests stay
        // deterministic and don't need real async waiting.
        rideService = new RideService(matchingService, osrmClient, pricingClient, tripClient, driverAssignmentService,
                meterRegistry, Runnable::run);
    }

    private RideResponse resolve(RideRequest req) throws ExecutionException, InterruptedException, TimeoutException {
        return rideService.requestRide(req).get(2, TimeUnit.SECONDS).getBody();
    }

    @Test
    void writesRequestedRowBeforeRankingCandidates() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000009");
        when(matchingService.rankCandidates(any())).thenReturn(List.of());

        resolve(request);

        verify(tripClient).recordRequested(eq("R-001"), any(), any());
        verify(tripClient).finalizeFailedNoMatch("T-00000009");
    }

    @Test
    void returnsSystemErrorImmediatelyWhenRequestedWriteFails() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any()))
                .thenThrow(new TripClient.TripUnavailableException(new RuntimeException("down")));

        RideResponse response = resolve(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        verifyNoInteractions(matchingService);
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void returnsFailedNoMatchAndLogsToTripWhenNoCandidates() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000001");
        when(matchingService.rankCandidates(any())).thenReturn(List.of());

        RideResponse response = resolve(request);

        assertThat(response.status()).isEqualTo("FAILED_NO_MATCH");
        verify(tripClient).finalizeFailedNoMatch("T-00000001");
        assertThat(meterRegistry.counter("ride_match_outcomes_total", "outcome", "failed_no_match").count())
                .isEqualTo(1.0);
    }

    @Test
    void returnsMatchedWithPriceAndTripIdOnSuccess() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000001");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        DistanceResult distance = new DistanceResult(5.0, 12.0, DistanceSource.OSRM);
        when(osrmClient.distanceAndDuration(any(), any())).thenReturn(Mono.just(distance));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);

        RideResponse response = resolve(request);

        assertThat(response.status()).isEqualTo("MATCHED");
        assertThat(response.tripId()).isEqualTo("T-00000001");
        assertThat(response.driverId()).isEqualTo("D-001");
        assertThat(response.price()).isEqualTo(14.5);
        assertThat(response.etaNote()).isNull();
        verify(tripClient).finalizeMatchedTrip(eq("T-00000001"), eq("D-001"), anyDouble(), any());
        assertThat(meterRegistry.counter("ride_match_outcomes_total", "outcome", "matched").count())
                .isEqualTo(1.0);
    }

    @Test
    void includesEtaNoteWhenWinnerIsStillOnTrip() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000002");
        CandidateScore winner = new CandidateScore("D-002", DriverStatus.ON_TRIP, 9.0, 3.0);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(Mono.just(new DistanceResult(5.0, 12.0, DistanceSource.OSRM)));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);

        RideResponse response = resolve(request);

        assertThat(response.driverStatus()).isEqualTo("ON_TRIP");
        assertThat(response.etaNote()).contains("finishing another trip");
    }

    @Test
    void returnsSystemErrorWhenPricingUnavailable() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000003");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(Mono.just(new DistanceResult(5.0, 12.0, DistanceSource.OSRM)));
        when(pricingClient.getQuote(any(), any(), any()))
                .thenThrow(new PricingClient.PricingUnavailableException(new RuntimeException("down")));

        RideResponse response = resolve(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        verify(tripClient).finalizeSystemError("T-00000003");
        verify(tripClient, never()).finalizeMatchedTrip(anyString(), anyString(), anyDouble(), any());
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void returnsSystemErrorWhenTripUnavailableAfterMatch() throws Exception {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000004");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(Mono.just(new DistanceResult(5.0, 12.0, DistanceSource.OSRM)));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);
        org.mockito.Mockito.doThrow(new TripClient.TripUnavailableException(new RuntimeException("down")))
                .when(tripClient).finalizeMatchedTrip(eq("T-00000004"), anyString(), anyDouble(), any());

        RideResponse response = resolve(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void rejectsWithServiceUnavailableWhenAdmissionGateIsFull() throws Exception {
        when(tripClient.recordRequested(anyString(), any(), any())).thenReturn("T-GATE");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));

        // OSRM never resolves until released below, so every requestRide() call
        // holds its admission permit for the duration of this test.
        CountDownLatch releaseGate = new CountDownLatch(1);
        when(osrmClient.distanceAndDuration(any(), any())).thenAnswer(invocation ->
                Mono.<DistanceResult>create(sink -> new Thread(() -> {
                    try {
                        releaseGate.await();
                        sink.success(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }).start()));

        List<CompletableFuture<ResponseEntity<RideResponse>>> inFlight = new ArrayList<>();
        for (int i = 0; i < RideService.ADMISSION_PERMITS; i++) {
            inFlight.add(rideService.requestRide(request));
        }

        // The gate is now fully held; this one must fail fast, not queue.
        long start = System.nanoTime();
        ResponseEntity<RideResponse> rejected = rideService.requestRide(request).get(2, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(rejected.getBody().status()).isEqualTo("SYSTEM_ERROR");
        assertThat(elapsedMs).isLessThan(500);

        releaseGate.countDown();
        for (CompletableFuture<ResponseEntity<RideResponse>> future : inFlight) {
            assertThat(future.get(2, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void cancelFreesAssociatedDriverReturnedByTrip() {
        when(tripClient.cancelTrip("T-001")).thenReturn("D-005");

        CancelResponse response = rideService.cancelTrip("T-001");

        assertThat(response.status()).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(driverAssignmentService).freeDriverForTrip("D-005");
    }
}
