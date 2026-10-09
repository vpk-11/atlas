package com.atlas.tripservice.trip;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Resolves REQUESTED rows left behind by a dispatch crash: anything older than the stale
 * threshold becomes SYSTEM_ERROR. Fails, does not re-run, since the original client has
 * given up (see decisions.md, v2 Phase 4).
 */
@Component
public class TripReconciler {

    private static final Logger log = LoggerFactory.getLogger(TripReconciler.class);

    private final TripRepository tripRepository;
    private final Counter reconciled;
    private final long staleSeconds;

    public TripReconciler(TripRepository tripRepository, MeterRegistry meterRegistry,
                          @Value("${trip.reconcile.stale-seconds}") long staleSeconds) {
        this.tripRepository = tripRepository;
        this.reconciled = meterRegistry.counter("trips_reconciled_total");
        this.staleSeconds = staleSeconds;
    }

    @Scheduled(fixedDelayString = "${trip.reconcile.interval-seconds}", timeUnit = TimeUnit.SECONDS)
    @Transactional
    public int sweep() {
        int n = tripRepository.updateStatusWhereOlderThan(TripStatus.REQUESTED, TripStatus.SYSTEM_ERROR,
                Instant.now().minusSeconds(staleSeconds));
        if (n > 0) {
            reconciled.increment(n);
            log.info("Reconciled {} stale REQUESTED trips to SYSTEM_ERROR", n);
        }
        return n;
    }
}
