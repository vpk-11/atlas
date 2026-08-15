package com.atlas.dispatchservice.osrm;

import com.atlas.dispatchservice.domain.Coordinate;
import com.atlas.dispatchservice.domain.DistanceResult;
import com.atlas.dispatchservice.domain.DistanceSource;
import com.atlas.dispatchservice.domain.RoadDistanceEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Component
public class OsrmClient {

    private static final Logger log = LoggerFactory.getLogger(OsrmClient.class);

    private final WebClient webClient;
    private final String baseUrl;
    private final Duration timeout;

    public OsrmClient(@Value("${osrm.base-url}") String baseUrl, @Value("${osrm.timeout-ms}") int timeoutMs) {
        this.baseUrl = baseUrl;
        this.timeout = Duration.ofMillis(timeoutMs);
        this.webClient = WebClient.builder().build();
    }

    /**
     * Non-blocking: the caller composes this into its own async chain rather
     * than blocking a request-handling thread for the OSRM round trip. Never
     * errors - a failed or slow OSRM call resolves to the circuity-factor
     * fallback instead, same as the previous blocking implementation's
     * try/catch.
     *
     * Note: timeout covers the whole call (connect + response) as a single
     * budget, unlike the old RestClient setup which gave connect and read
     * each their own separate 350ms window (so the old worst case could run
     * up to ~700ms before falling back). This is a stricter, not looser,
     * timeout - documented since it isn't byte-identical to the old semantics.
     */
    public Mono<DistanceResult> distanceAndDuration(Coordinate from, Coordinate to) {
        String path = "/route/v1/driving/%s,%s;%s,%s?overview=false".formatted(
                from.lng(), from.lat(), to.lng(), to.lat());

        return webClient.get()
                .uri(baseUrl + path)
                .retrieve()
                .bodyToMono(OsrmRouteResponse.class)
                // bodyToMono completes empty (no onNext) on an empty upstream body,
                // which would otherwise skip flatMap entirely and resolve to a null
                // DistanceResult downstream. Route that case into the same fallback
                // path as every other OSRM failure instead.
                .switchIfEmpty(Mono.error(new IllegalStateException("OSRM returned an empty response")))
                .timeout(timeout)
                .flatMap(response -> {
                    if (response.routes() == null || response.routes().isEmpty()) {
                        return Mono.<DistanceResult>error(new IllegalStateException("OSRM returned no routes"));
                    }
                    OsrmRouteResponse.Route route = response.routes().get(0);
                    return Mono.just(new DistanceResult(route.distance() / 1000.0, route.duration() / 60.0, DistanceSource.OSRM));
                })
                .onErrorResume(e -> {
                    log.warn("OSRM call failed ({}), falling back to circuity-factor estimate", e.toString());
                    double distanceKm = RoadDistanceEstimator.estimateRoadDistanceKm(from.lat(), from.lng(), to.lat(), to.lng());
                    double durationMinutes = RoadDistanceEstimator.estimateMinutes(from.lat(), from.lng(), to.lat(), to.lng());
                    return Mono.just(new DistanceResult(distanceKm, durationMinutes, DistanceSource.FALLBACK));
                });
    }
}
