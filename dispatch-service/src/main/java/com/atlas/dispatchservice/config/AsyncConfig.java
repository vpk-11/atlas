package com.atlas.dispatchservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Dedicated bounded pool for the blocking work (pricing/trip gRPC calls,
 * driver DB write) that runs after the OSRM call resolves. Deliberately
 * separate from both Tomcat's request-handling threads and Reactor's Netty
 * event-loop threads used by WebClient - blocking either of those would
 * defeat the point of making the OSRM call non-blocking in the first place.
 *
 * Sized from measured concurrency, not from the DB pool: at 500 req/s across 2
 * pods each pod runs about 250 req/s with roughly 120ms of work in this stage
 * (pricing gRPC, one driver DB write, trip gRPC), so average concurrency is about
 * 30 and a pool of 30 rejected every burst above the average (hundreds of
 * "executor at capacity" 503s per 180s run). 64 gives about 2x headroom. The DB
 * connection pool (HikariCP, 30, see application.yaml) still bounds actual DB
 * concurrency: each execution holds a connection only for its short driver write.
 *
 * queueCapacity is 0 (Spring's ThreadPoolTaskExecutor uses a SynchronousQueue
 * handoff at 0, not "no queue at all"): found via real sustained load-test
 * evidence that a deep queue (originally 200) defeats the whole point of the
 * admission-gate semaphore in RideService. The semaphore bounds how many
 * requests are admitted into the OSRM-onward pipeline, but with a 200-deep
 * queue behind only 30 workers, admitted requests could still sit queued for
 * 13-16s under real sustained load instead of failing fast - a second,
 * finer-grained instance of the same "queueing instead of fail-fast" bug the
 * semaphore was built to fix. Zero queue capacity means a task either gets a
 * free worker immediately or is rejected immediately (see RideService's
 * exceptionally() handler) - fail-fast enforced at both layers now, not just
 * the coarse one.
 */
@Configuration
public class AsyncConfig {

    private static final int POOL_SIZE = 64;
    private static final int QUEUE_CAPACITY = 0;

    @Bean
    public Executor postOsrmExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(POOL_SIZE);
        executor.setMaxPoolSize(POOL_SIZE);
        executor.setQueueCapacity(QUEUE_CAPACITY);
        executor.setThreadNamePrefix("post-osrm-");
        executor.initialize();
        return executor;
    }
}
