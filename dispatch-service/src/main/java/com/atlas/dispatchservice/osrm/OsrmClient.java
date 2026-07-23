package com.atlas.dispatchservice.osrm;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.domain.DistanceSource;
import com.atlas.dispatchservice.domain.RoadDistanceEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class OsrmClient {

    private static final Logger log = LoggerFactory.getLogger(OsrmClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public OsrmClient(@Value("${osrm.base-url}") String baseUrl, @Value("${osrm.timeout-ms}") int timeoutMs) {
        this.baseUrl = baseUrl;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    public DistanceResult distanceAndDuration(Coordinate from, Coordinate to) {
        try {
            String path = "/route/v1/driving/%s,%s;%s,%s?overview=false".formatted(
                    from.lng(), from.lat(), to.lng(), to.lat());
            OsrmRouteResponse response = restClient.get()
                    .uri(baseUrl + path)
                    .retrieve()
                    .body(OsrmRouteResponse.class);

            if (response == null || response.routes() == null || response.routes().isEmpty()) {
                throw new IllegalStateException("OSRM returned no routes");
            }

            OsrmRouteResponse.Route route = response.routes().get(0);
            return new DistanceResult(route.distance() / 1000.0, route.duration() / 60.0, DistanceSource.OSRM);
        } catch (Exception e) {
            log.warn("OSRM call failed ({}), falling back to circuity-factor estimate", e.toString());
            double distanceKm = RoadDistanceEstimator.estimateRoadDistanceKm(from.lat(), from.lng(), to.lat(), to.lng());
            double durationMinutes = RoadDistanceEstimator.estimateMinutes(from.lat(), from.lng(), to.lat(), to.lng());
            return new DistanceResult(distanceKm, durationMinutes, DistanceSource.FALLBACK);
        }
    }
}
