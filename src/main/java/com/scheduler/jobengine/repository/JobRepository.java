package com.scheduler.jobengine.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.scheduler.jobengine.model.Job;
import com.scheduler.jobengine.model.JobStatus;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    @Query("""
        SELECT j FROM Job j
        WHERE j.tenantId = :tenantId
        AND j.status IN (:statuses)
        AND j.scheduledAt <= :now
        ORDER BY j.scheduledAt ASC
    """)
    List<Job> findReadyJobsForTenant(
        @Param("tenantId") String tenantId,
        @Param("statuses") List<JobStatus> statuses,
        @Param("now") LocalDateTime now
    );

    @Query("""
        SELECT DISTINCT j.tenantId FROM Job j
        WHERE j.status IN (:statuses)
        AND j.scheduledAt <= :now
        AND j.status != 'RUNNING'
    """)
    List<String> findActiveTenantsWithPendingJobs(
        @Param("statuses") List<JobStatus> statuses,
        @Param("now") LocalDateTime now
    );

    @Query("""
        SELECT j.status, COUNT(j) FROM Job j
        WHERE j.tenantId = :tenantId
        GROUP BY j.status
    """)
    List<Object[]> countByStatusForTenant(@Param("tenantId") String tenantId);

    List<Job> findByStatus(JobStatus status);
}