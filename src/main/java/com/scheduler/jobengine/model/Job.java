package com.scheduler.jobengine.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs", indexes = {
    // these two indexes exist because the event loop and poller
    // hammer these exact query patterns every 500ms
    @Index(name = "idx_tenant_status", columnList = "tenant_id, status"),
    @Index(name = "idx_scheduled", columnList = "status, scheduled_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // tenant_id is the isolation boundary—every query filters by this.
    // without it, one company's jobs are visible to another. non-negotiable.
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    // storing payload as raw JSON string — keeps the schema flexible.
    // different job types have different payloads, no point normalizing this.
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private JobStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // scheduledAt controls when a job is eligible to run.
    // for new jobs it equals createdAt.
    // for retries it's pushed forward by the backoff calculation.
    @Column(name = "scheduled_at")
    private LocalDateTime scheduledAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.status == null) this.status = JobStatus.PENDING;
        // learned this the hard way — new jobs need scheduledAt set
        // immediately or the poller skips them on the first pass
        if (this.scheduledAt == null) this.scheduledAt = this.createdAt;
    }

    // ── state transitions ──────────────────────────────────────────────
    // keeping these on the model so the full job lifecycle is readable
    // in one place. service layer just calls these and saves.

    public boolean isReadyToRun() {
        boolean correctStatus = status == JobStatus.PENDING
                             || status == JobStatus.RETRYING;
        boolean scheduleCleared = !LocalDateTime.now().isBefore(scheduledAt);
        return correctStatus && scheduleCleared;
    }

    public void markRunning() {
        this.status = JobStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
    }

    public void markCompleted() {
        this.status = JobStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.errorMessage = null;
    }

    public void markFailed(String error, int maxRetries, long baseBackoffMs) {
        this.retryCount++;
        this.errorMessage = error;

        if (this.retryCount >= maxRetries) {
            // no point retrying anymore — mark it dead and move on
            this.status = JobStatus.DEAD;
            this.completedAt = LocalDateTime.now();
            return;
        }

        // exponential backoff: 5s → 10s → 20s
        // keeps the system from hammering a struggling dependency
        long delayMs = baseBackoffMs * (long) Math.pow(2, retryCount - 1);
        this.scheduledAt = LocalDateTime.now()
                .plusNanos(delayMs * 1_000_000);
        this.status = JobStatus.RETRYING;
    }

    public boolean isTerminal() {
        // TODO: might want to add a CANCELLED status later
        return status == JobStatus.COMPLETED || status == JobStatus.DEAD;
    }
}