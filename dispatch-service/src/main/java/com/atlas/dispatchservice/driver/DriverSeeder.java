package com.atlas.dispatchservice.driver;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class DriverSeeder implements CommandLineRunner {

    private static final int DRIVER_COUNT = 999;
    private static final double MIN_LAT = 37.70;
    private static final double MAX_LAT = 37.83;
    private static final double MIN_LNG = -122.51;
    private static final double MAX_LNG = -122.36;

    private final DriverRepository driverRepository;

    public DriverSeeder(DriverRepository driverRepository) {
        this.driverRepository = driverRepository;
    }

    @Override
    public void run(String... args) {
        if (driverRepository.count() > 0) {
            return;
        }

        Random random = new Random(42);
        List<Driver> drivers = new ArrayList<>(DRIVER_COUNT);
        for (int i = 1; i <= DRIVER_COUNT; i++) {
            String driverId = "D-%03d".formatted(i);
            double lat = MIN_LAT + random.nextDouble() * (MAX_LAT - MIN_LAT);
            double lng = MIN_LNG + random.nextDouble() * (MAX_LNG - MIN_LNG);
            drivers.add(new Driver(driverId, lat, lng, DriverStatus.AVAILABLE));
        }
        driverRepository.saveAll(drivers);
    }
}
