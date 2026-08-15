package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.driver.Driver;
import com.atlas.dispatchservice.driver.DriverRepository;
import com.atlas.dispatchservice.driver.DriverStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MatchingServiceTest {

    @Mock
    private GridIndex gridIndex;

    @Mock
    private DriverRepository driverRepository;

    private MatchingService matchingService;

    private static final Coordinate PICKUP = new Coordinate(37.7749, -122.4194);

    @BeforeEach
    void setUp() {
        matchingService = new MatchingService(gridIndex, driverRepository);
    }

    @Test
    void availableDriverCloserThanBusyOneWinsWhenGenuinelyFaster() {
        Driver available = new Driver("D-001", 37.71, -122.50, DriverStatus.AVAILABLE); // far corner, still in bounds
        Driver busy = new Driver("D-002", 37.7750, -122.4195, DriverStatus.ON_TRIP); // right next to pickup
        busy.setEstimatedFreeAt(Instant.now().plusSeconds(60));
        busy.setDestination(37.7751, -122.4196);

        mockCandidates(available, busy);

        List<CandidateScore> ranked = matchingService.rankCandidates(PICKUP);

        assertThat(ranked).hasSize(2);
        assertThat(ranked.get(0).driverId()).isEqualTo("D-002");
        assertThat(ranked.get(0).effectiveStatus()).isEqualTo(DriverStatus.ON_TRIP);
    }

    @Test
    void onTripDriverPastEstimatedFreeAtIsLazilyTreatedAsAvailable() {
        Driver expiredOnTrip = new Driver("D-003", 37.71, -122.50, DriverStatus.ON_TRIP);
        expiredOnTrip.setEstimatedFreeAt(Instant.now().minusSeconds(120));
        expiredOnTrip.setDestination(37.7749, -122.4194); // ended up right at pickup

        mockCandidates(expiredOnTrip);

        List<CandidateScore> ranked = matchingService.rankCandidates(PICKUP);

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).effectiveStatus()).isEqualTo(DriverStatus.AVAILABLE);
        assertThat(ranked.get(0).totalTimeToPickupMinutes()).isCloseTo(0.0, org.assertj.core.api.Assertions.within(0.5));
    }

    @Test
    void offlineDriversAreExcluded() {
        Driver offline = new Driver("D-004", 37.7749, -122.4194, DriverStatus.OFFLINE);
        mockCandidates(offline);

        List<CandidateScore> ranked = matchingService.rankCandidates(PICKUP);

        assertThat(ranked).isEmpty();
    }

    private void mockCandidates(Driver... drivers) {
        List<QuadTree.IndexedPoint> points = List.of(drivers).stream()
                .map(d -> new QuadTree.IndexedPoint(d.getDriverId(), d.getCurrentLat(), d.getCurrentLng()))
                .toList();
        when(gridIndex.candidatesNear(anyDouble(), anyDouble())).thenReturn(points);
        for (Driver driver : drivers) {
            when(driverRepository.findById(driver.getDriverId())).thenReturn(Optional.of(driver));
        }
    }
}
