package com.atlas.pricingservice.grpc;

import com.atlas.pricingservice.grpc.common.Coordinate;
import com.atlas.pricingservice.grpc.pricing.QuoteRequest;
import com.atlas.pricingservice.grpc.pricing.QuoteResponse;
import com.atlas.pricingservice.pricing.PricingCalculator;
import com.atlas.pricingservice.pricing.PricingProperties;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PricingGrpcServiceTest {

    @Test
    void returnsPriceFromCalculatorAndCompletesStream() {
        PricingGrpcService service = new PricingGrpcService(new PricingCalculator(new PricingProperties(2.0)));

        QuoteRequest request = QuoteRequest.newBuilder()
                .setPickup(Coordinate.newBuilder().setLat(42.0).setLng(-71.0).build())
                .setDrop(Coordinate.newBuilder().setLat(42.1).setLng(-71.1).build())
                .setDistanceKm(10.0)
                .setDurationMinutes(15.0)
                .build();

        @SuppressWarnings("unchecked")
        StreamObserver<QuoteResponse> responseObserver = mock(StreamObserver.class);

        service.getQuote(request, responseObserver);

        ArgumentCaptor<QuoteResponse> captor = ArgumentCaptor.forClass(QuoteResponse.class);
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();

        assertThat(captor.getValue().getPrice()).isEqualTo(20.0);
    }
}
