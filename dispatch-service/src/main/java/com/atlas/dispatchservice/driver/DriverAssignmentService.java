package com.atlas.dispatchservice.driver;

import com.atlas.dispatchservice.domain.Coordinate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class DriverAssignmentService {

    private final DriverRepository driverRepository;

    public DriverAssignmentService(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Transactional
    public void assignTrip(String driverId, Coordinate drop, double tripDurationMinutes) {
        Driver driver = driverRepository.findById(driverId)
                .orElseThrow(() -> new IllegalStateException("Driver not found: " + driverId));
        driver.setStatus(DriverStatus.ON_TRIP);
        driver.setEstimatedFreeAt(Instant.now().plusSeconds((long) (tripDurationMinutes * 60)));
        driver.setDestination(drop.lat(), drop.lng());
        driverRepository.save(driver);
    }

    @Transactional
    public void freeDriverForTrip(String driverId) {
        driverRepository.findById(driverId).ifPresent(driver -> {
            driver.setStatus(DriverStatus.AVAILABLE);
            driver.setEstimatedFreeAt(null);
            driver.setDestination(null, null);
            driverRepository.save(driver);
        });
    }
}
