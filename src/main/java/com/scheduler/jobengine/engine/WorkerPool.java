package com.scheduler.jobengine.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.scheduler.jobengine.config.JobEngineConfig;
import com.scheduler.jobengine.model.Job;

@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ExecutorService executor;
    private final LinkedBlockingQueue<JobResult> completionQueue;

    // result of a job execution — carries back to event loop
    public record JobResult(Job job, boolean success, String errorMessage) {}

    public WorkerPool(JobEngineConfig config) {
        // bounded pool — this is the ceiling on true parallelism.
        // more threads = more throughput but more memory.
        // 5 is enough to demonstrate the concept cleanly.
        this.executor = Executors.newFixedThreadPool(config.getWorkerPoolSize());
        this.completionQueue = new LinkedBlockingQueue<>();
        log.info("Worker pool initialized with {} threads", config.getWorkerPoolSize());
    }

    // ── called by event loop ───────────────────────────────────────────

    public void submit(Job job) {
        executor.submit(() -> {
            try {
                log.debug("Worker executing job {} type={} tenant={}",
                        job.getId(), job.getJobType(), job.getTenantId());

                // simulate actual job execution — different types take different time.
                // in a real system this would call actual business logic.
                executeJobByType(job);

                completionQueue.add(new JobResult(job, true, null));
                log.debug("Job {} completed successfully", job.getId());

            } catch (InterruptedException e) {
                // thread was interrupted — system is likely shutting down
                Thread.currentThread().interrupt();
                completionQueue.add(new JobResult(job, false, "Worker interrupted"));

            } catch (Exception e) {
                // job failed — pass error back to event loop for retry logic
                log.error("Job {} failed: {}", job.getId(), e.getMessage());
                completionQueue.add(new JobResult(job, false, e.getMessage()));

            } catch (Throwable t) {
                // catches OOM, StackOverflow etc — keeps the thread alive
                log.error("Critical failure in worker for job {}: {}",
                        job.getId(), t.getMessage(), t);
                completionQueue.add(new JobResult(job, false,
                        "Critical error: " + t.getMessage()));
            }
        });
    }

    // event loop calls this every tick to collect finished jobs
    public JobResult pollCompletion() {
        return completionQueue.poll(); // non-blocking — returns null if empty
    }

    // ── job type simulation ────────────────────────────────────────────

    private void executeJobByType(Job job) throws InterruptedException {
        // simulating different job durations by type.
        // TODO: replace with real handlers when extending to phase 2
        switch (job.getJobType()) {
            case "EXPORT_LEADS"     -> Thread.sleep(2000);
            case "SEND_EMAILS"      -> Thread.sleep(1500);
            case "GENERATE_REPORT"  -> Thread.sleep(3000);
            case "SYNC_CONTACTS"    -> Thread.sleep(1000);
            default                 -> Thread.sleep(500);
        }
    }

    // ── shutdown ───────────────────────────────────────────────────────

    public void shutdown() {
        log.info("Shutting down worker pool...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}