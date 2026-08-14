package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.driver.DriverRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Rebuilds GridIndex from the Driver table on a fixed schedule, independently
 * in each Dispatch pod. Chosen fix for the 2-replica consistency problem: no
 * shared external index, both replicas converge on the same view within one
 * refresh interval since both read the same MySQL Driver table.
 */
@Component
public class GridIndexRefresher {

    private final DriverRepository driverRepository;
    private final GridIndex gridIndex;

    public GridIndexRefresher(DriverRepository driverRepository, GridIndex gridIndex) {
        this.driverRepository = driverRepository;
        this.gridIndex = gridIndex;
    }

    @Scheduled(fixedRateString = "${grid.refresh-interval-ms:3000}")
    public void refresh() {
        gridIndex.rebuild(driverRepository.findAll());
    }
}
