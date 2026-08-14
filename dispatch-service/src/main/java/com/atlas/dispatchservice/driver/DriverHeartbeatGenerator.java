package com.atlas.dispatchservice.driver;

import com.atlas.dispatchservice.domain.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Simulated heartbeat source: nudges a small random sample of drivers' current
 * location on a fixed interval, same role as k6 simulating riders. Not real GPS.
 * Any Dispatch pod may fire this; whichever pod's timer wins for a given driver
 * just wins, no coordination needed, same lazy-check-friendly spirit as the rest
 * of the project's driver-state design.
 */
@Component
public class DriverHeartbeatGenerator {

    private static final Logger log = LoggerFactory.getLogger(DriverHeartbeatGenerator.class);

    private static final BoundingBox BOUNDS = BoundingBox.SAN_FRANCISCO;
    private static final double MAX_STEP_DEGREES = 0.002; // ponytail: flat degree step, not true distance-uniform
    private static final int DRIVERS_PER_TICK = 30;

    private final DriverRepository driverRepository;
    private final Random random = new Random();

    public DriverHeartbeatGenerator(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Scheduled(fixedRateString = "${heartbeat.interval-ms:2000}")
    public void tick() {
        // OFFLINE drivers have no phone reporting a position; only AVAILABLE and
        // ON_TRIP drivers heartbeat.
        List<Driver> reporting;
        try {
            reporting = driverRepository.findAll().stream()
                    .filter(d -> d.getStatus() != DriverStatus.OFFLINE)
                    .toList();
        } catch (Exception e) {
            log.error("Heartbeat tick failed reading driver table, skipping this tick: {}", e.toString());
            return;
        }
        if (reporting.isEmpty()) {
            return;
        }
        for (int i = 0; i < DRIVERS_PER_TICK; i++) {
            Driver driver = reporting.get(random.nextInt(reporting.size()));
            double newLat = clamp(driver.getCurrentLat() + step(), BOUNDS.minLat(), BOUNDS.maxLat());
            double newLng = clamp(driver.getCurrentLng() + step(), BOUNDS.minLng(), BOUNDS.maxLng());
            driver.setCurrentLocation(newLat, newLng);
            driverRepository.save(driver);
        }
    }

    private double step() {
        return (random.nextDouble() * 2 - 1) * MAX_STEP_DEGREES;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
