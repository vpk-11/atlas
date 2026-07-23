package com.atlas.pricingservice.grpc;

import com.atlas.pricingservice.grpc.pricing.PricingServiceGrpc;
import com.atlas.pricingservice.grpc.pricing.QuoteRequest;
import com.atlas.pricingservice.grpc.pricing.QuoteResponse;
import com.atlas.pricingservice.pricing.PricingCalculator;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@GrpcService
public class PricingGrpcService extends PricingServiceGrpc.PricingServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(PricingGrpcService.class);

    private final PricingCalculator calculator;

    public PricingGrpcService(PricingCalculator calculator) {
        this.calculator = calculator;
    }

    @Override
    public void getQuote(QuoteRequest request, StreamObserver<QuoteResponse> responseObserver) {
        double price = calculator.priceFor(request.getDistanceKm());
        log.info("Quoted price {} for distance {} km", price, request.getDistanceKm());

        QuoteResponse response = QuoteResponse.newBuilder()
                .setPrice(price)
                .build();
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}
