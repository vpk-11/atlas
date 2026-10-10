package com.atlas.tripservice.trip;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the idempotency, claim, status-guard and sweep paths against a real MySQL, because the
 * unique constraint, the conditional updates and the race behaviour are exactly what mocks cannot
 * show. Skipped when Docker is not available.
 */
@SpringBootTest(properties = "grpc.server.port=0")
@Testcontainers(disabledWithoutDocker = true)
class TripIdempotencyIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer mysql = new MySQLContainer("mysql:8.4.3");

    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripRecordService tripRecordService;

    @BeforeEach
    void clean() {
        tripRepository.deleteAll();
    }

    private TripRecordService.RecordResult record(TripRecordService service, String rider, String key) {
        return service.recordTrip(rider, "", 37.7, -122.4, 37.8, -122.4, 0.0, 0.0, 0.0, null,
                TripStatus.REQUESTED, key);
    }

    @Test
    void sameRiderAndKeyTwiceIsOneRowAndTheSecondIsAReplay() {
        TripRecordService.RecordResult first = record(tripRecordService, "R-001", "K-1");
        TripRecordService.RecordResult second = record(tripRecordService, "R-001", "K-1");

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.trip().getTripId()).isEqualTo(first.trip().getTripId());
        assertThat(tripRepository.count()).isEqualTo(1);
    }

    @Test
    void sameKeyFromADifferentRiderIsItsOwnTripAndNeverLeaksTheOtherRidersTrip() {
        TripRecordService.RecordResult a = record(tripRecordService, "R-001", "K-1");
        TripRecordService.RecordResult b = record(tripRecordService, "R-002", "K-1");

        assertThat(b.replayed()).isFalse();
        assertThat(b.trip().getTripId()).isNotEqualTo(a.trip().getTripId());
        assertThat(tripRepository.count()).isEqualTo(2);
    }

    @Test
    void rowsWithoutAKeyAreNeverTreatedAsReplays() {
        record(tripRecordService, "R-001", null);
        TripRecordService.RecordResult second = record(tripRecordService, "R-001", null);

        assertThat(second.replayed()).isFalse();
        assertThat(tripRepository.count()).isEqualTo(2);
    }

    @Test
    void manyConcurrentRequestsWithOneKeyYieldOneRowOneOwnerAndNoOneElseResuming() throws Exception {
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TripRecordService.RecordResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<TripRecordService.RecordResult> call = () -> {
                start.await();
                return record(tripRecordService, "R-001", "K-RACE");
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<TripRecordService.RecordResult> results = new ArrayList<>();
        for (Future<TripRecordService.RecordResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(tripRepository.count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.replayed())).hasSize(1);
        // Everyone else saw the owner's fresh lease, so none may run a second match.
        assertThat(results.stream().filter(TripRecordService.RecordResult::resumeGranted)).isEmpty();
        assertThat(results.stream().map(r -> r.trip().getTripId()).distinct()).hasSize(1);
    }

    @Test
    void replayIsGrantedTheClaimOnlyOnceTheLeaseExpires() {
        record(tripRecordService, "R-001", "K-LEASE");

        TripRecordService longLease = new TripRecordService(tripRepository, 3600);
        assertThat(record(longLease, "R-001", "K-LEASE").resumeGranted()).isFalse();

        TripRecordService expiredLease = new TripRecordService(tripRepository, 0);
        assertThat(record(expiredLease, "R-001", "K-LEASE").resumeGranted()).isTrue();
        // The takeover renewed the lease, so a further replay under a real lease loses.
        assertThat(record(longLease, "R-001", "K-LEASE").resumeGranted()).isFalse();
    }

    @Test
    void replayOfARetriableSystemErrorIsGrantedOnceAndReopensTheRow() {
        TripRecordService.RecordResult first = record(tripRecordService, "R-001", "K-SE");
        String tripId = first.trip().getTripId();
        tripRecordService.updateTripStatus(tripId, "", 0.0, 0.0, 0.0, DistanceSource.FALLBACK, TripStatus.SYSTEM_ERROR);

        TripRecordService.RecordResult replay = record(tripRecordService, "R-001", "K-SE");
        TripRecordService.RecordResult second = record(tripRecordService, "R-001", "K-SE");

        assertThat(replay.resumeGranted()).isTrue();
        assertThat(second.resumeGranted()).isFalse(); // already reopened and leased by the first replay
        assertThat(tripRepository.findByTripId(tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.REQUESTED);
    }

    @Test
    void settledTripsAreNeverReclaimedOrOverwritten() {
        String tripId = record(tripRecordService, "R-001", "K-OK").trip().getTripId();
        tripRecordService.updateTripStatus(tripId, "D-1", 9.0, 1.0, 1.0, DistanceSource.OSRM, TripStatus.MATCHED);

        assertThat(record(tripRecordService, "R-001", "K-OK").resumeGranted()).isFalse();

        // A late SYSTEM_ERROR must not overwrite MATCHED, and a late MATCHED must not undo CANCELLED.
        tripRecordService.updateTripStatus(tripId, "", 0.0, 0.0, 0.0, DistanceSource.FALLBACK, TripStatus.SYSTEM_ERROR);
        assertThat(tripRepository.findByTripId(tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.MATCHED);

        tripRecordService.cancelTrip(tripId);
        tripRecordService.updateTripStatus(tripId, "D-1", 9.0, 1.0, 1.0, DistanceSource.OSRM, TripStatus.MATCHED);
        assertThat(tripRepository.findByTripId(tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.CANCELLED);
    }

    @Test
    void aLateFinalizeAfterTheSweepStillSettlesTheTrip() {
        String tripId = record(tripRecordService, "R-001", "K-LATE").trip().getTripId();
        new TripReconciler(tripRepository, new SimpleMeterRegistry(), 0).sweep();
        assertThat(tripRepository.findByTripId(tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.SYSTEM_ERROR);

        tripRecordService.updateTripStatus(tripId, "D-1", 9.0, 1.0, 1.0, DistanceSource.OSRM, TripStatus.MATCHED);

        assertThat(tripRepository.findByTripId(tripId).orElseThrow().getStatus()).isEqualTo(TripStatus.MATCHED);
    }

    @Test
    void sweepFailsOnlyStaleRequestedRows() {
        String stale = record(tripRecordService, "R-001", "K-STALE").trip().getTripId();
        String settled = record(tripRecordService, "R-002", "K-SETTLED").trip().getTripId();
        tripRecordService.updateTripStatus(settled, "D-1", 9.0, 1.0, 1.0, DistanceSource.OSRM, TripStatus.MATCHED);

        int tooFresh = new TripReconciler(tripRepository, new SimpleMeterRegistry(), 3600).sweep();
        int swept = new TripReconciler(tripRepository, new SimpleMeterRegistry(), 0).sweep();

        assertThat(tooFresh).isZero();
        assertThat(swept).isEqualTo(1);
        assertThat(tripRepository.findByTripId(stale).orElseThrow().getStatus()).isEqualTo(TripStatus.SYSTEM_ERROR);
        assertThat(tripRepository.findByTripId(settled).orElseThrow().getStatus()).isEqualTo(TripStatus.MATCHED);
    }
}
