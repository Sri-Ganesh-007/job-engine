package com.scheduler.jobengine.simulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.scheduler.jobengine.model.Job;
import com.scheduler.jobengine.model.JobStatus;
import com.scheduler.jobengine.repository.JobRepository;

@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JobRepository jobRepository;

    public DataSeeder(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void run(String... args) {
        log.info("Seeding simulated tenant jobs...");

        // tenant_a — noisy neighbor, floods 10 jobs
        // this is the tenant that would starve everyone else in a naive FIFO queue
        for (int i = 1; i <= 10; i++) {
            jobRepository.save(Job.builder()
                    .tenantId("tenant_a")
                    .jobType("EXPORT_LEADS")
                    .payload("{\"batch\": " + i + "}")
                    .status(JobStatus.PENDING)
                    .retryCount(0)
                    .build());
        }

        // tenant_b — single urgent job
        // should NOT wait behind all of tenant_a's jobs
        jobRepository.save(Job.builder()
                .tenantId("tenant_b")
                .jobType("SEND_EMAILS")
                .payload("{\"campaign_id\": \"launch_2024\"}")
                .status(JobStatus.PENDING)
                .retryCount(0)
                .build());

        // tenant_c — moderate load
        for (int i = 1; i <= 5; i++) {
            jobRepository.save(Job.builder()
                    .tenantId("tenant_c")
                    .jobType("GENERATE_REPORT")
                    .payload("{\"report_type\": \"quarterly\", \"quarter\": " + i + "}")
                    .status(JobStatus.PENDING)
                    .retryCount(0)
                    .build());
        }

        log.info("Seeded 16 jobs across 3 tenants | tenant_a=10 tenant_b=1 tenant_c=5");
        log.info("Watch logs to verify tenant_b's job runs early despite tenant_a's volume");
    }
}
