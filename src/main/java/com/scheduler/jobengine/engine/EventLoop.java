package com.scheduler.jobengine.engine;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.scheduler.jobengine.config.JobEngineConfig;
import com.scheduler.jobengine.model.Job;
import com.scheduler.jobengine.model.JobStatus;
import com.scheduler.jobengine.repository.JobRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class EventLoop {

    private static final Logger log = LoggerFactory.getLogger(EventLoop.class);

    private final TenantQueueManager queueManager;
    private final WorkerPool workerPool;
    private final JobRepository jobRepository;
    private final JobEngineConfig config;

    private final AtomicLong lastTickTime = new AtomicLong(System.currentTimeMillis());

    private final ScheduledExecutorService loopExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "event-loop-thread");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService pollerExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "db-poller-thread");
                t.setDaemon(true);
                return t;
            });

    private final ScheduledExecutorService watchdogExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "watchdog-thread");
                t.setDaemon(true);
                return t;
            });

    public EventLoop(TenantQueueManager queueManager,
                     WorkerPool workerPool,
                     JobRepository jobRepository,
                     JobEngineConfig config) {
        this.queueManager = queueManager;
        this.workerPool = workerPool;
        this.jobRepository = jobRepository;
        this.config = config;
    }

    @PostConstruct
    public void start() {
        log.info("Starting job engine...");

        pollerExecutor.scheduleAtFixedRate(
                this::pollDatabase,
                0,
                config.getEventLoopIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        loopExecutor.scheduleAtFixedRate(
                this::tick,
                500,
                config.getEventLoopIntervalMs(),
                TimeUnit.MILLISECONDS
        );

        watchdogExecutor.scheduleAtFixedRate(
                this::checkLoopHealth,
                5000,
                5000,
                TimeUnit.MILLISECONDS
        );

        log.info("Job engine started | interval={}ms workers={}",
                config.getEventLoopIntervalMs(),
                config.getWorkerPoolSize());
    }

    private void pollDatabase() {
        try {
            List<String> activeTenants = jobRepository
                    .findActiveTenantsWithPendingJobs(
                            List.of(JobStatus.PENDING, JobStatus.RETRYING),
                            LocalDateTime.now()
                    );

            for (String tenantId : activeTenants) {
                List<Job> readyJobs = jobRepository.findReadyJobsForTenant(
                        tenantId,
                        List.of(JobStatus.PENDING, JobStatus.RETRYING),
                        LocalDateTime.now()
                );
                readyJobs.forEach(queueManager::enqueue);
            }

        } catch (Exception e) {
            log.error("DB poller error: {}", e.getMessage(), e);
        }
    }

    private void tick() {
        try {
            lastTickTime.set(System.currentTimeMillis());

            // collect completed jobs first
            WorkerPool.JobResult result;
            while ((result = workerPool.pollCompletion()) != null) {
                handleCompletion(result);
            }

            // dispatch next job using round robin
            Job job = queueManager.nextJob();
            if (job != null) {
                job.markRunning();
                jobRepository.save(job);
                workerPool.submit(job);
                log.debug("Dispatched job {} tenant={}",
                        job.getId(), job.getTenantId());
            }

        } catch (Exception e) {
            log.error("Event loop tick error: {}", e.getMessage(), e);
        }
    }

    private void handleCompletion(WorkerPool.JobResult result) {
        Job job = result.job();
        try {
            if (result.success()) {
                job.markCompleted();
                log.info("Job {} COMPLETED | tenant={}",
                        job.getId(), job.getTenantId());
            } else {
                job.markFailed(
                        result.errorMessage(),
                        config.getMaxRetryCount(),
                        config.getRetryBackoffMs()
                );
                log.warn("Job {} {} | tenant={} attempt={} error={}",
                        job.getId(),
                        job.getStatus(),
                        job.getTenantId(),
                        job.getRetryCount(),
                        result.errorMessage()
                );
            }
            jobRepository.save(job);
            queueManager.markDone(job.getId()); // release from dedup map

        } catch (Exception e) {
            log.error("Failed to handle completion for job {}: {}",
                    job.getId(), e.getMessage(), e);
        }
    }

    private void checkLoopHealth() {
        long msSinceLastTick = System.currentTimeMillis() - lastTickTime.get();
        long threshold = config.getEventLoopIntervalMs() * 3;

        if (msSinceLastTick > threshold) {
            log.error("WATCHDOG: Event loop blocked! Last tick {}ms ago " +
                    "(threshold {}ms)", msSinceLastTick, threshold);
        } else {
            log.debug("Watchdog: loop healthy | last tick {}ms ago",
                    msSinceLastTick);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Stopping job engine...");
        loopExecutor.shutdown();
        pollerExecutor.shutdown();
        watchdogExecutor.shutdown();
        workerPool.shutdown();
    }

    public long getLastTickTime() {
        return lastTickTime.get();
    }
}