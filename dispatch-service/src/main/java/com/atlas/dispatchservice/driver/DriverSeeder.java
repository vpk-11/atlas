package com.atlas.dispatchservice.driver;

import com.atlas.dispatchservice.domain.BoundingBox;
import com.atlas.dispatchservice.matching.GridIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class DriverSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DriverSeeder.class);
    private static final int DRIVER_COUNT = 999;
    private static final BoundingBox BOUNDS = BoundingBox.SAN_FRANCISCO;

    private final DriverRepository driverRepository;
    private final GridIndex gridIndex;

    public DriverSeeder(DriverRepository driverRepository, GridIndex gridIndex) {
        this.driverRepository = driverRepository;
        this.gridIndex = gridIndex;
    }

    @Override
    public void run(String... args) {
        if (driverRepository.count() == 0) {
            try {
                seed();
            } catch (DataIntegrityViolationException e) {
                // Two replicas can both pass the count()==0 check before either
                // commits (startup race with 2+ dispatch pods against one MySQL).
                // Whoever loses just uses the rows the winner already wrote.
                log.info("Driver table already seeded by another replica, skipping.");
            }
        }
        gridIndex.rebuild(driverRepository.findAll());
    }

    private void seed() {
        Random random = new Random(42);
        List<Driver> drivers = new ArrayList<>(DRIVER_COUNT);
        for (int i = 1; i <= DRIVER_COUNT; i++) {
            String driverId = "D-%03d".formatted(i);
            double lat = BOUNDS.minLat() + random.nextDouble() * (BOUNDS.maxLat() - BOUNDS.minLat());
            double lng = BOUNDS.minLng() + random.nextDouble() * (BOUNDS.maxLng() - BOUNDS.minLng());
            drivers.add(new Driver(driverId, lat, lng, DriverStatus.AVAILABLE));
        }
        driverRepository.saveAll(drivers);
    }
}
