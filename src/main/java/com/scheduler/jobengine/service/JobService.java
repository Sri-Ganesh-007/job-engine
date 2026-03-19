package com.scheduler.jobengine.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.scheduler.jobengine.model.Job;
import com.scheduler.jobengine.model.JobStatus;
import com.scheduler.jobengine.repository.JobRepository;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    // ── called by API layer ────────────────────────────────────────────

    public Job submit(String tenantId, String jobType, String payload) {
        try {
            Job job = Job.builder()
                    .tenantId(tenantId)
                    .jobType(jobType)
                    .payload(payload)
                    .status(JobStatus.PENDING)
                    .retryCount(0)
                    .build();

            Job saved = jobRepository.save(job);
            log.info("Job submitted | id={} tenant={} type={}",
                    saved.getId(), tenantId, jobType);
            return saved;

        } catch (Exception e) {
            log.error("Failed to submit job for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new RuntimeException("Job submission failed: " + e.getMessage(), e);
        }
    }

    public List<Job> getJobsForTenant(String tenantId) {
        try {
            // tenant can only see their own jobs — isolation enforced here too
            return jobRepository.findReadyJobsForTenant(
                    tenantId,
                    List.of(JobStatus.PENDING, JobStatus.RUNNING,
                            JobStatus.RETRYING, JobStatus.COMPLETED,
                            JobStatus.DEAD),
                    java.time.LocalDateTime.now().plusYears(1)
            );
        } catch (Exception e) {
            log.error("Failed to fetch jobs for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new RuntimeException("Failed to fetch jobs: " + e.getMessage(), e);
        }
    }

    public Map<String, Long> getStatsForTenant(String tenantId) {
        try {
            List<Object[]> raw = jobRepository.countByStatusForTenant(tenantId);
            Map<String, Long> stats = new HashMap<>();

            // initialize all statuses to 0 so response is always complete
            for (JobStatus status : JobStatus.values()) {
                stats.put(status.name(), 0L);
            }

            for (Object[] row : raw) {
                JobStatus status = (JobStatus) row[0];
                Long count = (Long) row[1];
                stats.put(status.name(), count);
            }

            return stats;

        } catch (Exception e) {
            log.error("Failed to get stats for tenant {}: {}", tenantId, e.getMessage(), e);
            throw new RuntimeException("Failed to get stats: " + e.getMessage(), e);
        }
    }
}