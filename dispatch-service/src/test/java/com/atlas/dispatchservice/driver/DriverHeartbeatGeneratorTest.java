package com.atlas.dispatchservice.driver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DriverHeartbeatGeneratorTest {

    @Mock
    private DriverRepository driverRepository;

    @Test
    void tickSkipsOfflineDriversAndActsOnReportingDrivers() {
        Driver offline = new Driver("D-OFF", 37.75, -122.40, DriverStatus.OFFLINE);
        Driver available = new Driver("D-AVAIL", 37.75, -122.40, DriverStatus.AVAILABLE);
        when(driverRepository.findAll()).thenReturn(List.of(offline, available));

        new DriverHeartbeatGenerator(driverRepository).tick();

        // Only 1 non-OFFLINE driver in the pool: all 30 per-tick draws hit it
        // deterministically. If the OFFLINE filter is removed, both drivers
        // become eligible and P(D-OFF never drawn across 30 draws) ~= 1e-9 -
        // this fails almost certainly on a mutation, not just on paper.
        verify(driverRepository, never()).save(argThat(d -> d.getDriverId().equals("D-OFF")));
        verify(driverRepository, atLeastOnce()).save(argThat(d -> d.getDriverId().equals("D-AVAIL")));
    }
}
