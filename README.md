# job-engine

A multi-tenant job scheduling engine built in Java.

The idea came from thinking about how platforms like Salesforce run background jobs
for thousands of companies on the same infrastructure — exports, emails, reports —
without one company's workload slowing down another's. This is my attempt at building
the core of that system from scratch.

## the problem

Most job queue implementations fall apart in a multi-tenant setup:

- one tenant floods the queue and starves everyone else
- jobs from different companies running on shared servers can leak data
- a job that crashes halfway just disappears
- thread-per-job blows up memory under any real load

## how it works
```
REST API -> JobService -> DB
                          ↑
                     DB Poller Thread
                     (every 500ms)
                          ↓
                TenantQueueManager
                (per-tenant queues)
                          ↓
                     Event Loop
                   (round-robin)
                          ↓
                    Worker Pool
                   (5 threads)
                          ↓
                  CompletionQueue
                          ↓
                Event Loop handles
                result, updates DB
```

three threads, each with one job:

- **event loop** — dispatches work, never touches the DB, never blocks
- **db poller** — reads pending jobs from DB, loads into memory
- **worker pool** — actually runs the jobs, posts results back

the event loop only works with in-memory structures. DB access is
fully isolated to the poller thread. this is what keeps the loop non-blocking.

## problems solved

**fair scheduling** — each tenant gets its own queue. round-robin dispatch
means tenant_a having 10k jobs doesn't delay tenant_b's single job.

**tenant isolation** — tenant_id is on every DB query, enforced at the
repository layer. there's no code path that returns jobs without a tenant filter.

**failure recovery** — jobs are a state machine persisted to DB.
failed jobs retry with exponential backoff (5s -> 10s -> 20s).
exceed the retry limit and they're marked DEAD, not silently dropped.

**deduplication** — poller runs every 500ms so the same job could get
enqueued multiple times before a worker finishes it. concurrent ID map prevents this.

**watchdog** — separate thread that checks if the event loop has ticked
recently. logs a critical alert if it hasn't. same pattern vert.x uses.

## stack

- Java 17, Spring Boot 3.2
- Spring Data JPA, H2
- Maven

## run it
```bash
mvn spring-boot:run
```

starts on port 8080. on startup a seeder creates 3 tenants with 16 jobs:

- tenant_a -> 10 jobs (the noisy neighbor)
- tenant_b -> 1 job (shouldn't be starved)
- tenant_c -> 5 jobs

watch the logs. tenant_b dispatches early despite tenant_a's volume.

## endpoints

| method | endpoint | what it does |
|--------|----------|--------------|
| POST | `/api/jobs` | submit a job |
| GET | `/api/jobs?tenantId=` | get jobs for a tenant |
| GET | `/api/jobs/stats?tenantId=` | counts by status |
| GET | `/api/health` | health check |
```bash
# submit a job
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/json" \
  -d '{"tenantId":"tenant_a","jobType":"EXPORT_LEADS","payload":"{}"}'

# check pipeline stats
curl http://localhost:8080/api/jobs/stats?tenantId=tenant_a
```

## H2 console
```
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:jobengine
username: sa
password: (leave empty)
```

good for verifying isolation live:
```sql
SELECT tenant_id, status, COUNT(*) FROM jobs GROUP BY tenant_id, status;
```

## what i'd do differently

- postgres instead of H2 so jobs survive restarts
- priority levels within fair scheduling
- proper load simulator — configurable tenant count, job volume, failure rate
- metrics per tenant — avg execution time, failure rate
- dead letter queue instead of just marking jobs DEAD

## roadmap

| phase | status |
|-------|--------|
| core engine | done |
| observability + metrics | in progress |
| load simulator | planned |
| priority queues | planned |
| postgres + dead letter queue | planned |