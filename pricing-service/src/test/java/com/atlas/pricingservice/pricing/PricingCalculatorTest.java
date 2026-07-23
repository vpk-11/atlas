package com.atlas.pricingservice.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingCalculatorTest {

    @Test
    void pricesDistanceLinearlyByBaseRate() {
        PricingCalculator calculator = new PricingCalculator(new PricingProperties(2.0));

        assertThat(calculator.priceFor(10.0)).isEqualTo(20.0);
    }

    @Test
    void zeroDistanceIsFree() {
        PricingCalculator calculator = new PricingCalculator(new PricingProperties(2.0));

        assertThat(calculator.priceFor(0.0)).isEqualTo(0.0);
    }

    @Test
    void scalesWithConfiguredBaseRate() {
        PricingCalculator calculator = new PricingCalculator(new PricingProperties(3.5));

        assertThat(calculator.priceFor(4.0)).isEqualTo(14.0);
    }
}
