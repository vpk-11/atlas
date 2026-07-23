package com.atlas.pricingservice.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pricing")
public record PricingProperties(double baseRatePerKm) {
}
