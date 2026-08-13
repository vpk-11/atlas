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

import java.util.List;

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
        rideService = new RideService(matchingService, osrmClient, pricingClient, tripClient, driverAssignmentService,
                meterRegistry);
    }

    @Test
    void writesRequestedRowBeforeRankingCandidates() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000009");
        when(matchingService.rankCandidates(any())).thenReturn(List.of());

        rideService.requestRide(request);

        verify(tripClient).recordRequested(eq("R-001"), any(), any());
        verify(tripClient).finalizeFailedNoMatch("T-00000009");
    }

    @Test
    void returnsSystemErrorImmediatelyWhenRequestedWriteFails() {
        when(tripClient.recordRequested(eq("R-001"), any(), any()))
                .thenThrow(new TripClient.TripUnavailableException(new RuntimeException("down")));

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        verifyNoInteractions(matchingService);
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void returnsFailedNoMatchAndLogsToTripWhenNoCandidates() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000001");
        when(matchingService.rankCandidates(any())).thenReturn(List.of());

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("FAILED_NO_MATCH");
        verify(tripClient).finalizeFailedNoMatch("T-00000001");
        assertThat(meterRegistry.counter("ride_match_outcomes_total", "outcome", "failed_no_match").count())
                .isEqualTo(1.0);
    }

    @Test
    void returnsMatchedWithPriceAndTripIdOnSuccess() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000001");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        DistanceResult distance = new DistanceResult(5.0, 12.0, DistanceSource.OSRM);
        when(osrmClient.distanceAndDuration(any(), any())).thenReturn(distance);
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);

        RideResponse response = rideService.requestRide(request);

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
    void includesEtaNoteWhenWinnerIsStillOnTrip() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000002");
        CandidateScore winner = new CandidateScore("D-002", DriverStatus.ON_TRIP, 9.0, 3.0);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);

        RideResponse response = rideService.requestRide(request);

        assertThat(response.driverStatus()).isEqualTo("ON_TRIP");
        assertThat(response.etaNote()).contains("finishing another trip");
    }

    @Test
    void returnsSystemErrorWhenPricingUnavailable() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000003");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any()))
                .thenThrow(new PricingClient.PricingUnavailableException(new RuntimeException("down")));

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        verify(tripClient).finalizeSystemError("T-00000003");
        verify(tripClient, never()).finalizeMatchedTrip(anyString(), anyString(), anyDouble(), any());
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void returnsSystemErrorWhenTripUnavailableAfterMatch() {
        when(tripClient.recordRequested(eq("R-001"), any(), any())).thenReturn("T-00000004");
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);
        org.mockito.Mockito.doThrow(new TripClient.TripUnavailableException(new RuntimeException("down")))
                .when(tripClient).finalizeMatchedTrip(eq("T-00000004"), anyString(), anyDouble(), any());

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
        assertThat(meterRegistry.getMeters()).isEmpty();
    }

    @Test
    void cancelFreesAssociatedDriverReturnedByTrip() {
        when(tripClient.cancelTrip("T-001")).thenReturn("D-005");

        CancelResponse response = rideService.cancelTrip("T-001");

        assertThat(response.status()).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(driverAssignmentService).freeDriverForTrip("D-005");
    }
}
