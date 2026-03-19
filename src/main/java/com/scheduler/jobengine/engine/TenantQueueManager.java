package com.scheduler.jobengine.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.scheduler.jobengine.model.Job;

@Component
public class TenantQueueManager {

    private static final Logger log = LoggerFactory.getLogger(TenantQueueManager.class);

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<Job>> tenantQueues
            = new ConcurrentHashMap<>();

    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    // prevents the DB poller from enqueuing the same job twice
    // before the worker finishes it
    private final ConcurrentHashMap<Long, Boolean> enqueuedJobIds
            = new ConcurrentHashMap<>();

    public void enqueue(Job job) {
        if (enqueuedJobIds.putIfAbsent(job.getId(), Boolean.TRUE) != null) {
            return; // already queued, skip silently
        }

        tenantQueues.computeIfAbsent(job.getTenantId(),
                id -> new ConcurrentLinkedQueue<>()).add(job);

        log.debug("Enqueued job {} for tenant {} | queue size: {}",
                job.getId(), job.getTenantId(),
                tenantQueues.get(job.getTenantId()).size());
    }

    public Job nextJob() {
        List<String> tenants = new ArrayList<>(tenantQueues.keySet());
        if (tenants.isEmpty()) return null;

        Collections.sort(tenants);
        int total = tenants.size();

        for (int i = 0; i < total; i++) {
            int index = roundRobinIndex.getAndIncrement() % total;
            String tenantId = tenants.get(index);
            ConcurrentLinkedQueue<Job> queue = tenantQueues.get(tenantId);

            if (queue != null) {
                Job job = queue.poll();
                if (job != null) {
                    log.debug("Dispatching job {} for tenant {} | remaining: {}",
                            job.getId(), tenantId, queue.size());
                    return job;
                }
            }
        }
        return null;
    }

    // called after job completes — removes from dedup map
    // so if a job is requeued for retry, it can be enqueued again
    public void markDone(Long jobId) {
        enqueuedJobIds.remove(jobId);
    }

    public int totalPending() {
        return tenantQueues.values().stream()
                .mapToInt(ConcurrentLinkedQueue::size)
                .sum();
    }

    public void clear() {
        tenantQueues.clear();
        enqueuedJobIds.clear();
        log.debug("All tenant queues cleared");
    }
}