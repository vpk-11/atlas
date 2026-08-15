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
 * Sized to match HikariCP's maximum-pool-size (30, see application.yaml):
 * each concurrent execution here holds at most one DB connection (via
 * DriverAssignmentService), so running more of these concurrently than the
 * DB pool can actually serve wouldn't help.
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

    private static final int POOL_SIZE = 30;
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
