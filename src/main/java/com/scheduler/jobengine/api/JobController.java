package com.scheduler.jobengine.api;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.scheduler.jobengine.model.Job;
import com.scheduler.jobengine.service.JobService;

@RestController
@RequestMapping("/api")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    // submit a new job
    // POST /api/jobs
    // body: { "tenantId": "company_a", "jobType": "EXPORT_LEADS", "payload": "{}" }
    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(@RequestBody JobRequest request) {
        try {
            if (request.tenantId() == null || request.tenantId().isBlank()) {
                return ResponseEntity.badRequest().body("tenantId is required");
            }
            if (request.jobType() == null || request.jobType().isBlank()) {
                return ResponseEntity.badRequest().body("jobType is required");
            }

            Job job = jobService.submit(
                    request.tenantId(),
                    request.jobType(),
                    request.payload()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(job);

        } catch (Exception e) {
            log.error("POST /jobs failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to submit job: " + e.getMessage());
        }
    }

    // get all jobs for a tenant
    // GET /api/jobs?tenantId=company_a
    @GetMapping("/jobs")
    public ResponseEntity<?> getJobs(@RequestParam String tenantId) {
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body("tenantId is required");
            }

            List<Job> jobs = jobService.getJobsForTenant(tenantId);
            return ResponseEntity.ok(jobs);

        } catch (Exception e) {
            log.error("GET /jobs failed for tenant {}: {}", tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to fetch jobs: " + e.getMessage());
        }
    }

    // pipeline stats for a tenant
    // GET /api/jobs/stats?tenantId=company_a
    @GetMapping("/jobs/stats")
    public ResponseEntity<?> getStats(@RequestParam String tenantId) {
        try {
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.badRequest().body("tenantId is required");
            }

            Map<String, Long> stats = jobService.getStatsForTenant(tenantId);
            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            log.error("GET /jobs/stats failed for tenant {}: {}",
                    tenantId, e.getMessage(), e);
            return ResponseEntity.internalServerError()
                    .body("Failed to fetch stats: " + e.getMessage());
        }
    }

    // health check — useful for demo
    // GET /api/health
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of(
                "status", "running",
                "engine", "multi-tenant-job-scheduler",
                "version", "1.0-phase1"
        ));
    }

    // request body record — clean and minimal
    public record JobRequest(String tenantId, String jobType, String payload) {}
}