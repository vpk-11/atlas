package com.atlas.dispatchservice.matching;

import com.atlas.dispatchservice.driver.DriverStatus;

public record CandidateScore(
        String driverId,
        DriverStatus effectiveStatus,
        double totalTimeToPickupMinutes
) {
}
