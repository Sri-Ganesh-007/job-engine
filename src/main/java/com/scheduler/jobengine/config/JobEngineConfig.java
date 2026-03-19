package com.scheduler.jobengine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;

@Configuration
@Getter
public class JobEngineConfig {

    @Value("${job.engine.worker-pool-size}")
    private int workerPoolSize;

    @Value("${job.engine.event-loop-interval-ms}")
    private long eventLoopIntervalMs;

    @Value("${job.engine.max-retry-count}")
    private int maxRetryCount;

    @Value("${job.engine.retry-backoff-ms}")
    private long retryBackoffMs;
}