package com.atlas.dispatchservice.pricing;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.grpc.pricing.PricingServiceGrpc;
import com.atlas.dispatchservice.grpc.pricing.QuoteRequest;
import com.atlas.dispatchservice.grpc.pricing.QuoteResponse;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PricingClient {

    private static final Logger log = LoggerFactory.getLogger(PricingClient.class);

    @GrpcClient("pricing-service")
    private PricingServiceGrpc.PricingServiceBlockingStub pricingStub;

    public double getQuote(Coordinate pickup, Coordinate drop, DistanceResult distance) {
        QuoteRequest request = QuoteRequest.newBuilder()
                .setPickup(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(pickup.lat()).setLng(pickup.lng()).build())
                .setDrop(com.atlas.dispatchservice.grpc.common.Coordinate.newBuilder()
                        .setLat(drop.lat()).setLng(drop.lng()).build())
                .setDistanceKm(distance.distanceKm())
                .setDurationMinutes(distance.durationMinutes())
                .build();

        try {
            return callQuote(request);
        } catch (Exception firstFailure) {
            log.warn("Pricing gRPC call failed, retrying once: {}", firstFailure.toString());
            try {
                return callQuote(request);
            } catch (Exception secondFailure) {
                throw new PricingUnavailableException(secondFailure);
            }
        }
    }

    private double callQuote(QuoteRequest request) {
        QuoteResponse response = pricingStub.getQuote(request);
        return response.getPrice();
    }

    public static class PricingUnavailableException extends RuntimeException {
        public PricingUnavailableException(Throwable cause) {
            super("Pricing service unreachable after retry", cause);
        }
    }
}
