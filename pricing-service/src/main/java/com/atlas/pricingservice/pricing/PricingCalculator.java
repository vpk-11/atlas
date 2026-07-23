package com.atlas.pricingservice.pricing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

// Distance/duration arrive already resolved by Dispatch (OSRM or its circuity-factor
// fallback) - this service never estimates distance itself, only prices a known one.
@Component
@EnableConfigurationProperties(PricingProperties.class)
public class PricingCalculator {

    private final PricingProperties properties;

    public PricingCalculator(PricingProperties properties) {
        this.properties = properties;
    }

    public double priceFor(double distanceKm) {
        return properties.baseRatePerKm() * distanceKm;
    }
}
