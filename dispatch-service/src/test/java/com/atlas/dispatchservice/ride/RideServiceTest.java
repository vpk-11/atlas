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

    private final RideRequest request = new RideRequest("R-001",
            new CoordinateDto(37.7749, -122.4194), new CoordinateDto(37.80, -122.41));

    @BeforeEach
    void setUp() {
        rideService = new RideService(matchingService, osrmClient, pricingClient, tripClient, driverAssignmentService);
    }

    @Test
    void returnsFailedNoMatchAndLogsToTripWhenNoCandidates() {
        when(matchingService.rankCandidates(any())).thenReturn(List.of());

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("FAILED_NO_MATCH");
        org.mockito.Mockito.verify(tripClient).recordFailedNoMatch(eq("R-001"), any(), any());
    }

    @Test
    void returnsMatchedWithPriceAndTripIdOnSuccess() {
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        DistanceResult distance = new DistanceResult(5.0, 12.0, DistanceSource.OSRM);
        when(osrmClient.distanceAndDuration(any(), any())).thenReturn(distance);
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);
        when(tripClient.recordMatchedTrip(anyString(), anyString(), any(), any(), anyDouble(), any()))
                .thenReturn("T-00000001");

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("MATCHED");
        assertThat(response.tripId()).isEqualTo("T-00000001");
        assertThat(response.driverId()).isEqualTo("D-001");
        assertThat(response.price()).isEqualTo(14.5);
        assertThat(response.etaNote()).isNull();
    }

    @Test
    void includesEtaNoteWhenWinnerIsStillOnTrip() {
        CandidateScore winner = new CandidateScore("D-002", DriverStatus.ON_TRIP, 9.0, 3.0);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);
        when(tripClient.recordMatchedTrip(anyString(), anyString(), any(), any(), anyDouble(), any()))
                .thenReturn("T-00000002");

        RideResponse response = rideService.requestRide(request);

        assertThat(response.driverStatus()).isEqualTo("ON_TRIP");
        assertThat(response.etaNote()).contains("finishing another trip");
    }

    @Test
    void returnsSystemErrorWhenPricingUnavailable() {
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any()))
                .thenThrow(new PricingClient.PricingUnavailableException(new RuntimeException("down")));

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    void returnsSystemErrorWhenTripUnavailableAfterMatch() {
        CandidateScore winner = new CandidateScore("D-001", DriverStatus.AVAILABLE, 4.0, null);
        when(matchingService.rankCandidates(any())).thenReturn(List.of(winner));
        when(osrmClient.distanceAndDuration(any(), any()))
                .thenReturn(new DistanceResult(5.0, 12.0, DistanceSource.OSRM));
        when(pricingClient.getQuote(any(), any(), any())).thenReturn(14.5);
        when(tripClient.recordMatchedTrip(anyString(), anyString(), any(), any(), anyDouble(), any()))
                .thenThrow(new TripClient.TripUnavailableException(new RuntimeException("down")));

        RideResponse response = rideService.requestRide(request);

        assertThat(response.status()).isEqualTo("SYSTEM_ERROR");
    }

    @Test
    void cancelFreesAssociatedDriverReturnedByTrip() {
        when(tripClient.cancelTrip("T-001")).thenReturn("D-005");

        CancelResponse response = rideService.cancelTrip("T-001");

        assertThat(response.status()).isEqualTo("CANCELLED");
        org.mockito.Mockito.verify(driverAssignmentService).freeDriverForTrip("D-005");
    }
}
