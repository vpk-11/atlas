package com.atlas.dispatchservice.driver;

import com.atlas.dispatchservice.domain.BoundingBox;
import com.atlas.dispatchservice.matching.QuadTreeIndex;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class DriverSeeder implements CommandLineRunner {

    private static final int DRIVER_COUNT = 999;
    private static final BoundingBox BOUNDS = BoundingBox.SAN_FRANCISCO;

    private final DriverRepository driverRepository;
    private final QuadTreeIndex quadTreeIndex;

    public DriverSeeder(DriverRepository driverRepository, QuadTreeIndex quadTreeIndex) {
        this.driverRepository = driverRepository;
        this.quadTreeIndex = quadTreeIndex;
    }

    @Override
    public void run(String... args) {
        if (driverRepository.count() == 0) {
            seed();
        }
        quadTreeIndex.rebuild(driverRepository.findAll());
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
