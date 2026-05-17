# AroundU Geo-Search Architecture

> How PostgreSQL + Redis work together for proximity-based job discovery.

---

## Table of Contents

1. [Overview](#overview)
2. [Data Flow: `getWorkerFeed()`](#data-flow-getworkerfeed)
3. [PostgreSQL ↔ Redis Synchronisation](#postgresql--redis-synchronisation)
4. [Retry Mechanism (FailedGeoSync)](#retry-mechanism-failedgeosync)
5. [Cache Eviction Strategy](#cache-eviction-strategy)
6. [Distance Enrichment & Sorting](#distance-enrichment--sorting)
7. [Redis CLI Debugging](#redis-cli-debugging)
8. [Consistency Verification](#consistency-verification)
9. [Performance Tuning](#performance-tuning)
10. [Identified & Fixed Logic Errors](#identified--fixed-logic-errors)

---

## Overview

```
┌──────────────────────────────────────────────────────────┐
│                      Client Request                      │
│              GET /api/v1/worker/feed?radius=5            │
└──────────────────────┬───────────────────────────────────┘
                       ▼
            ┌─────────────────────┐
            │    JobServiceImpl   │
            │   getWorkerFeed()   │
            └─────┬─────────┬─────┘
                  │         │
          ① Geo   │         │ ③ Fetch full job
           query  │         │    details (PG)
                  ▼         ▼
         ┌──────────┐  ┌──────────────┐
         │  Redis   │  │ PostgreSQL   │
         │ GEO SET  │  │    jobs      │
         │ (index)  │  │  (source of  │
         └──────────┘  │   truth)     │
                       └──────────────┘
```

**Architecture principle**: Redis is an **index only** — it stores `{jobId → (lon, lat)}` for fast
radius lookups. PostgreSQL is always the **source of truth** for job data and status. Every geo result
from Redis is re-validated against PostgreSQL before being returned to the client.

**Key**: `geo:jobs:open` (Redis Sorted Set with geo encoding)

---

## Data Flow: `getWorkerFeed()`

```
JobServiceImpl.getWorkerFeed(workerId, request)
│
├─ 1. Load Worker from PG → extract (lat, lon) from worker.currentAddress
│
├─ 2. Call jobGeoService.findNearbyOpenJobs(lat, lon, radiusKm, limit)
│     │
│     └─ RedisJobGeoService → GEORADIUS geo:jobs:open lon lat radiusKm km
│        ASC COUNT limit → returns List<Long> jobIds
│
├─ 3a. If geoJobIds is NOT empty:
│      → jobRepository.findByIdInAndJobStatus(geoJobIds, OPEN_FOR_BIDS, pageable)
│      → Re-validates status in PG (stale Redis entries return empty)
│
├─ 3b. If geoJobIds IS empty (no geo results or worker has no location):
│      → jobRepository.findOpenJobsBySkills(OPEN_FOR_BIDS, skillIds, pageable)
│      → Skill-based fallback search
│
├─ 4. In-memory skill filtering (if request.skillIds is set)
│     → Jobs without matching skills are removed
│
├─ 5. DTO mapping + distance enrichment + popularity enrichment
│     → enrichWithDistance() sets distanceKm using Haversine formula
│     → enrichWithPopularity() sets popularityScore based on bid count
│
├─ 6. Virtual sorting (if sortByDistance=true or sortBy="distance")
│     → In-memory Comparator sorts by distanceKm (ASC/DESC)
│
└─ 7. Return PageImpl(dtos, pageable, dtos.size())
       → Total elements = filtered count (not DB page total)
```

### Key Implementation Details

| Step          | Class                | Method                              |
| ------------- | -------------------- | ----------------------------------- |
| Geo query     | `RedisJobGeoService` | `findNearbyOpenJobs()`              |
| PG validation | `JobRepository`      | `findByIdInAndJobStatus()`          |
| Skill filter  | `JobServiceImpl`     | inline loop in `getWorkerFeed()`    |
| Distance      | `DistanceUtils`      | `haversine(lat1, lon1, lat2, lon2)` |
| Sorting       | `JobServiceImpl`     | `buildVirtualComparator()`          |

---

## PostgreSQL ↔ Redis Synchronisation

### Write Operations

| Operation                     | PG Action                | Redis Action                                  | Safety Wrapper    |
| ----------------------------- | ------------------------ | --------------------------------------------- | ----------------- |
| Create Job (OPEN_FOR_BIDS)    | `jobRepository.save()`   | `GEOADD geo:jobs:open lon lat jobId`          | `safeGeoAdd()`    |
| Update Job (location changed) | `jobRepository.save()`   | `GEOADD geo:jobs:open lon lat jobId` (upsert) | `safeGeoAdd()`    |
| Delete Job                    | `jobRepository.delete()` | `ZREM geo:jobs:open jobId`                    | `safeGeoRemove()` |
| Status → non-OPEN             | `jobRepository.save()`   | `ZREM geo:jobs:open jobId`                    | `safeGeoRemove()` |

### Sync Mechanisms

#### 1. Startup Sync (`syncOpenJobsToGeoIndex`)

- **When**: `ApplicationReadyEvent` (app start)
- **What**: Loads all `OPEN_FOR_BIDS` jobs from PG, calls `GEOADD` for each
- **Why**: Ensures Redis index matches PG after restart or Redis flush

#### 2. Daily Cleanup (`cleanupStaleGeoEntries`)

- **When**: `@Scheduled(cron = "0 0 2 * * ?")` — 02:00 AM daily
- **What**: Gets all geo members from Redis, cross-references with PG open job IDs, removes stale entries
- **Why**: Catches any drift between PG and Redis (e.g., missed events)

#### 3. Retry Failed Syncs (`retryFailedGeoSyncs`)

- **When**: `@Scheduled(fixedDelay = 300_000, initialDelay = 60_000)` — every 5 min
- **What**: Fetches unresolved `FailedGeoSync` records, re-attempts the operation
- **Why**: Handles transient Redis failures without blocking the PG transaction

#### 4. Event-Driven Cache Eviction (`onJobModified`)

- **When**: `@TransactionalEventListener(AFTER_COMMIT)`
- **What**: Evicts Spring caches (job:detail, job:client:list, job:worker:feed)
- **Why**: Keeps cached responses fresh after mutations

---

## Retry Mechanism (FailedGeoSync)

### Flow

```
JobServiceImpl.safeGeoAdd(jobId, lat, lon)
│
├─ try: jobGeoService.addOrUpdateOpenJob(jobId, lat, lon)  ✓ success
│
└─ catch: recordFailedSync(jobId, ADD, lat, lon, error)
          │
          └─ INSERT INTO failed_geo_syncs (job_id, operation, lat, lon, ...)
```

### Entity: `failed_geo_syncs`

| Column        | Type                      | Description                |
| ------------- | ------------------------- | -------------------------- |
| `id`          | BIGINT (PK)               | Auto-generated             |
| `job_id`      | BIGINT                    | The affected job           |
| `operation`   | ENUM(ADD, REMOVE, UPDATE) | What failed                |
| `latitude`    | DOUBLE                    | Coordinates for ADD/UPDATE |
| `longitude`   | DOUBLE                    | Coordinates for ADD/UPDATE |
| `retry_count` | INT                       | Attempts so far (max 5)    |
| `last_error`  | VARCHAR(1000)             | Last failure message       |
| `created_at`  | TIMESTAMP                 | When the failure occurred  |
| `resolved`    | BOOLEAN                   | Whether it's been fixed    |

### Retry Logic (every 5 minutes)

```java
for each unresolved record where retryCount < 5:
  switch (operation):
    ADD/UPDATE:
      if job exists and is still OPEN_FOR_BIDS → re-GEOADD
      else → mark resolved (no action needed)
    REMOVE:
      → re-ZREM (idempotent)

  on success → mark resolved
  on failure → increment retryCount, save lastError
```

After 5 failed retries, the record is abandoned (not picked up again). The daily cleanup
at 02:00 AM serves as the ultimate consistency guarantee.

---

## Cache Eviction Strategy

### Cache Regions

| Region            | TTL     | Key Pattern                     | Eviction Trigger                                                     |
| ----------------- | ------- | ------------------------------- | -------------------------------------------------------------------- |
| `job:detail`      | 30 min  | `job:detail::{jobId}`           | Any mutation on that job                                             |
| `job:client:list` | 10 min  | `job:client:list::{clientId}:*` | Mutation by that client                                              |
| `job:worker:feed` | 5 min   | `job:worker:feed::*`            | CREATE, DELETE, STATUS_CHANGE, or UPDATE with `locationChanged=true` |
| `user:profile`    | 1 hour  | `user:profile::{userId}`        | Profile updates                                                      |
| `worker:skills`   | 6 hours | `worker:skills::{workerId}`     | Skill changes                                                        |

### Event-Driven Eviction Rules

```java
@TransactionalEventListener(phase = AFTER_COMMIT)
public void onJobModified(JobModifiedEvent event) {
    evictJobDetail(event.jobId());           // always
    evictClientJobsCaches(event.clientId());  // always

    // Worker feed only for structural changes or location updates
    if (event.type() != UPDATED || event.locationChanged()) {
        evictWorkerFeedCaches();
    }
}
```

**Why `locationChanged` matters**: A simple title edit shouldn't invalidate every worker's
cached feed. Only location changes affect geo-search results and require feed cache invalidation.

---

## Distance Enrichment & Sorting

### Haversine Formula

```java
// Earth radius: 6,371 km
double dLat = toRadians(lat2 - lat1);
double dLon = toRadians(lon2 - lon1);
double a = sin(dLat/2)² + cos(lat1) * cos(lat2) * sin(dLon/2)²
double c = 2 * atan2(√a, √(1-a))
return 6371.0 * c  // km
```

**Accuracy**: ±0.5% for distances under 100 km (sufficient for local service matching).

### Distance Enrichment

In `getWorkerFeed()`, every returned DTO is enriched:

```java
if (workerLat != null && workerLon != null) {
    double km = DistanceUtils.haversine(workerLat, workerLon, jobLat, jobLon);
    dto.setDistanceKm(Math.round(km * 100.0) / 100.0);  // 2 decimal places
}
```

### Distance Sorting

When `sortByDistance=true` or `sortBy="distance"`:

- Default direction: follows `sortDirection` (DESC by default)
- For "nearest first", set `sortDirection=ASC`
- Null distances sort last (`Comparator.nullsLast`)

---

## Redis CLI Debugging

### View all indexed jobs

```bash
ZRANGE geo:jobs:open 0 -1
```

### Check if a specific job is indexed

```bash
ZSCORE geo:jobs:open "42"
```

### Get coordinates of a job

```bash
GEOPOS geo:jobs:open "42"
```

### Find jobs within 5km of a point

```bash
GEORADIUS geo:jobs:open -74.006 40.7128 5 km ASC COUNT 20
```

### Get distance between two jobs

```bash
GEODIST geo:jobs:open "1" "2" km
```

### Count total indexed jobs

```bash
ZCARD geo:jobs:open
```

### Remove a stale entry manually

```bash
ZREM geo:jobs:open "42"
```

---

## Consistency Verification

### Quick Health Check

```sql
-- Count of OPEN_FOR_BIDS jobs in PostgreSQL
SELECT COUNT(*) FROM jobs WHERE job_status = 'OPEN_FOR_BIDS'
  AND job_location_id IS NOT NULL;
```

```bash
# Count of jobs in Redis geo-index
ZCARD geo:jobs:open
```

These numbers should be equal (±1 for in-flight transactions).

### Find Orphaned Redis Entries

```sql
-- Jobs in Redis but not OPEN in PG
-- First: ZRANGE geo:jobs:open 0 -1  →  note the job IDs
-- Then for each ID:
SELECT id, job_status FROM jobs WHERE id = <redis_job_id>;
```

### Find Missing Redis Entries

```sql
-- Jobs that should be in Redis but aren't
SELECT j.id, a.latitude, a.longitude
FROM jobs j
JOIN addresses a ON j.job_location_id = a.id
WHERE j.job_status = 'OPEN_FOR_BIDS'
  AND a.latitude IS NOT NULL
  AND a.longitude IS NOT NULL;
-- Compare with: ZRANGE geo:jobs:open 0 -1
```

### Check Failed Syncs

```sql
SELECT * FROM failed_geo_syncs
WHERE resolved = false
ORDER BY created_at DESC;
```

### Force Re-sync

Restart the application — the startup sync (`syncOpenJobsToGeoIndex`) will re-populate
the entire geo index from PostgreSQL.

---

## Performance Tuning

### Redis GEORADIUS Optimisation

**Before (bug)**: Used Java `.stream().limit()` on GEORADIUS results — Redis returned ALL
matching jobs, transferred them over the network, then Java discarded most of them.

**After (fix)**: `GEORADIUS ... ASC COUNT N` — Redis sorts and limits server-side, returning
only the N nearest jobs. Network transfer is O(N) instead of O(all matches).

```java
// ✅ Current: server-side sort + limit
GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
        .sortAscending()
        .limit(Math.max(limit, 1));
var results = ops.radius(OPEN_JOBS_GEO_KEY, within, args);
```

### Fetch Multiplier

The default limit passed to Redis is `size * 3` (e.g., page size 20 → fetch 60 from Redis).
This over-fetches to account for:

- Jobs that are no longer `OPEN_FOR_BIDS` in PG (stale Redis entries)
- Jobs filtered out by skill matching
- Jobs on other pages

### Index Size Considerations

| Job Count | GEORADIUS Latency | Memory  |
| --------- | ----------------- | ------- |
| 1,000     | < 1ms             | ~50 KB  |
| 10,000    | 1-2ms             | ~500 KB |
| 100,000   | 2-5ms             | ~5 MB   |
| 1,000,000 | 5-15ms            | ~50 MB  |

Redis geo operations are O(N+log(M)) where N = results returned and M = total elements.

---

## Identified & Fixed Logic Errors

### Bug 1: GEORADIUS returned arbitrary subset instead of nearest N

**File**: `RedisJobGeoService.findNearbyOpenJobs()`

**Before** (broken):

```java
var results = ops.radius(OPEN_JOBS_GEO_KEY, within);  // no sort, no limit
return results.getContent().stream()
    .map(...)
    .limit(Math.max(limit, 0))  // Java-side limit on unsorted results
    .collect(Collectors.toList());
```

**After** (fixed):

```java
GeoRadiusCommandArgs args = GeoRadiusCommandArgs.newGeoRadiusArgs()
    .sortAscending()        // nearest first
    .limit(Math.max(limit, 1));  // Redis-side limit
var results = ops.radius(OPEN_JOBS_GEO_KEY, within, args);
```

**Impact**: Workers could see jobs 20km away while missing jobs 2km away.  
**Test**: `WorkerFeedRadiusTests.multipleJobsSortedByDistance`

---

### Bug 2: No retry mechanism for failed Redis writes

**Before** (broken):

```java
// In createJob():
jobGeoService.addOrUpdateOpenJob(job.getId(), lat, lon);
// If this throws → entire transaction rolls back (PG + geo fail together)
// OR: exception is swallowed silently, geo-index permanently misses the job
```

**After** (fixed):

```java
private void safeGeoAdd(Long jobId, Double lat, Double lon) {
    try {
        jobGeoService.addOrUpdateOpenJob(jobId, lat, lon);
    } catch (Exception ex) {
        log.error("Redis geo ADD failed for jobId={}", jobId});
        recordFailedSync(jobId, SyncOperation.ADD, lat, lon, ex.getMessage());
    }
}
// + FailedGeoSync entity + scheduled retryFailedGeoSyncs() every 5 min
```

**Impact**: Transient Redis failures could permanently exclude jobs from geo search.  
**Test**: `FailedGeoSyncRetryTests` (5 tests)

---

### Bug 3: PageImpl total elements mismatch after skill filtering

**Before** (broken):

```java
// After in-memory skill filtering removed some jobs:
return new PageImpl<>(dtos, pageable, jobsPage.getTotalElements());
// getTotalElements() = DB count (e.g., 50), but dtos.size() = 3 after filtering
// → Frontend thinks there are 50 results, shows 47 empty pages
```

**After** (fixed):

```java
return new PageImpl<>(dtos, pageable, dtos.size());
// Total reflects the actual filtered count
```

**Impact**: Pagination metadata was wrong — client showed more pages than existed.  
**Test**: `SkillFilteringTests.pageImplTotalMatchesFilteredCount`

---

### Bug 4: Worker feed cache NOT evicted on location update

**Before** (broken):

```java
// JobModifiedEvent only had 3 fields:
public record JobModifiedEvent(Long jobId, Long clientId, Type type) {}

// onJobModified() evicted worker feed for ALL update types
// OR: never evicted worker feed for updates (depends on implementation)
```

**After** (fixed):

```java
public record JobModifiedEvent(Long jobId, Long clientId, Type type, boolean locationChanged) {}

// Only evict worker feed when location actually changed:
if (event.type() != UPDATED || event.locationChanged()) {
    cacheEvictionService.evictWorkerFeedCaches();
}
```

**Impact**: Workers saw stale geo results after a job's location was updated, OR every
minor edit invalidated all workers' cached feeds unnecessarily.  
**Test**: `LocationChangeEvictionTests` (2 tests)

---

### Bug 5: Redis write failure could block PG transaction

**Before** (broken):

```java
// Direct call in createJob() — no try-catch:
jobGeoService.addOrUpdateOpenJob(job.getId(), lat, lon);
// Redis timeout → @Transactional rolls back the PG save too
```

**After** (fixed):

```java
private void safeGeoAdd(Long jobId, Double lat, Double lon) {
    try {
        jobGeoService.addOrUpdateOpenJob(jobId, lat, lon);
    } catch (Exception ex) {
        log.error("Redis geo ADD failed, scheduling retry", ex);
        recordFailedSync(jobId, SyncOperation.ADD, lat, lon, ex.getMessage());
    }
}
```

**Impact**: A Redis outage would prevent new jobs from being created in PostgreSQL.  
**Test**: `FailedGeoSyncRetryTests.retryIncrementsCountOnFailure`

---

## Test Coverage

| Suite                         | Tests | What It Covers                                           |
| ----------------------------- | ----- | -------------------------------------------------------- |
| `HaversineTests`              | 7     | Distance formula accuracy, symmetry, edge cases          |
| `WorkerFeedRadiusTests`       | 7     | Radius search, fallback, sorting, defaults               |
| `GeoIndexLifecycleTests`      | 2     | Add/remove from geo index on status changes              |
| `SkillFilteringTests`         | 2     | Skill filtering + PageImpl total correction              |
| `FailedGeoSyncRetryTests`     | 5     | Retry add/remove, skip closed jobs, increment on failure |
| `LocationChangeEvictionTests` | 2     | Location change → evict; no change → skip                |
| `DistanceEnrichmentTests`     | 2     | Distance set with location, null without                 |
| `RedisIndexVerificationTests` | 2     | PG is source of truth, graceful geo failure              |

**Total**: 29 geo-specific tests in `GeoSearchTest.java`  
**Full suite**: 181 tests, all passing
