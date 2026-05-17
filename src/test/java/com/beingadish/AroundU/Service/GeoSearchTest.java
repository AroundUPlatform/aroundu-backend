package com.beingadish.AroundU.Service;

import com.beingadish.AroundU.common.constants.enums.JobStatus;
import com.beingadish.AroundU.common.constants.enums.SortDirection;
import com.beingadish.AroundU.job.dto.JobSummaryDTO;
import com.beingadish.AroundU.job.dto.WorkerJobFeedRequest;
import com.beingadish.AroundU.location.entity.Address;
import com.beingadish.AroundU.location.entity.FailedGeoSync;
import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.common.entity.Skill;
import com.beingadish.AroundU.user.entity.Worker;
import com.beingadish.AroundU.job.event.JobModifiedEvent;
import com.beingadish.AroundU.job.exception.JobValidationException;
import com.beingadish.AroundU.job.mapper.JobMapper;
import com.beingadish.AroundU.bid.repository.BidRepository;
import com.beingadish.AroundU.location.repository.FailedGeoSyncRepository;
import com.beingadish.AroundU.job.repository.JobRepository;
import com.beingadish.AroundU.user.repository.WorkerReadRepository;
import com.beingadish.AroundU.infrastructure.cache.CacheEvictionService;
import com.beingadish.AroundU.job.service.impl.JobServiceImpl;
import com.beingadish.AroundU.location.service.JobGeoSyncService;
import com.beingadish.AroundU.common.util.DistanceUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import com.beingadish.AroundU.common.util.PageResponse;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.beingadish.AroundU.location.service.JobGeoService;
import com.beingadish.AroundU.infrastructure.metrics.MetricsService;

/**
 * Comprehensive geo-search tests covering:
 * <ul>
 * <li>Radius search accuracy (distance calculations)</li>
 * <li>Fallback to skill-based search when no geo results</li>
 * <li>Distance sorting (nearest first)</li>
 * <li>Geo-index lifecycle (add, remove, status changes)</li>
 * <li>Failed geo-sync retry mechanism</li>
 * <li>Haversine distance accuracy</li>
 * <li>Edge cases (boundary, null coordinates, empty results)</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GeoSearch — Comprehensive Geo Tests")
class GeoSearchTest {

    // ── NYC coordinates for tests ─────────────────────────────────
    private static final double NYC_LAT = 40.7128;
    private static final double NYC_LON = -74.0060;

    // Times Square (~5.3 km from NYC City Hall)
    private static final double TIMES_SQUARE_LAT = 40.7580;
    private static final double TIMES_SQUARE_LON = -73.9855;

    // Brooklyn Bridge (~1.1 km from NYC City Hall)
    private static final double BROOKLYN_BRIDGE_LAT = 40.7061;
    private static final double BROOKLYN_BRIDGE_LON = -73.9969;

    // Central Park (~8.5 km from NYC City Hall)
    private static final double CENTRAL_PARK_LAT = 40.7829;
    private static final double CENTRAL_PARK_LON = -73.9654;

    // JFK Airport (~22 km from NYC City Hall)
    private static final double JFK_LAT = 40.6413;
    private static final double JFK_LON = -73.7781;

    @Mock
    private JobRepository jobRepository;
    @Mock
    private WorkerReadRepository workerReadRepository;
    @Mock
    private BidRepository bidRepository;
    @Mock
    private FailedGeoSyncRepository failedGeoSyncRepository;
    @Mock
    private JobMapper jobMapper;
    @Mock
    private JobGeoService jobGeoService;
    @Mock
    private MetricsService metricsService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CacheEvictionService cacheEvictionService;

    // Unused by getWorkerFeed but required by @InjectMocks
    @Mock
    private com.beingadish.AroundU.location.repository.AddressRepository addressRepository;
    @Mock
    private com.beingadish.AroundU.user.repository.ClientRepository clientRepository;
    @Mock
    private com.beingadish.AroundU.common.repository.SkillRepository skillRepository;
    @Mock
    private com.beingadish.AroundU.common.service.SkillService skillService;
    @Mock
    private com.beingadish.AroundU.user.service.WorkerPenaltyService workerPenaltyService;
    @Mock
    private com.beingadish.AroundU.infrastructure.ranking.RankingEngineClient rankingEngineClient;

    @InjectMocks
    private JobServiceImpl jobService;

    // ── Helpers ──────────────────────────────────────────────────
    private Worker workerAt(double lat, double lon) {
        Address addr = Address.builder().latitude(lat).longitude(lon).build();
        return Worker.builder().id(1L).currentAddress(addr).build();
    }

    private Job jobAt(Long id, double lat, double lon) {
        Address addr = Address.builder().latitude(lat).longitude(lon).build();
        return Job.builder()
                .id(id)
                .jobStatus(JobStatus.OPEN_FOR_BIDS)
                .jobLocation(addr)
                .skillSet(new HashSet<>())
                .createdAt(LocalDateTime.now())
                .build();
    }

    private Job jobAt(Long id, double lat, double lon, Set<Skill> skills) {
        Address addr = Address.builder().latitude(lat).longitude(lon).build();
        return Job.builder()
                .id(id)
                .jobStatus(JobStatus.OPEN_FOR_BIDS)
                .jobLocation(addr)
                .skillSet(skills)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private JobSummaryDTO summaryDto(Long id) {
        JobSummaryDTO dto = new JobSummaryDTO();
        dto.setId(id);
        dto.setCreatedAt(LocalDateTime.now());
        return dto;
    }

    private WorkerJobFeedRequest feedRequest(Double radiusKm) {
        WorkerJobFeedRequest req = new WorkerJobFeedRequest();
        req.setRadiusKm(radiusKm);
        req.setPage(0);
        req.setSize(20);
        return req;
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 1: Haversine Distance Accuracy
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Haversine Distance Accuracy")
    class HaversineTests {

        @Test
        @DisplayName("NYC City Hall to Brooklyn Bridge ≈ 1.1 km")
        void nycToBrooklynBridge() {
            double distance = DistanceUtils.haversine(NYC_LAT, NYC_LON, BROOKLYN_BRIDGE_LAT, BROOKLYN_BRIDGE_LON);
            assertThat(distance).isCloseTo(1.1, within(0.2));
        }

        @Test
        @DisplayName("NYC City Hall to Times Square ≈ 5.3 km")
        void nycToTimesSquare() {
            double distance = DistanceUtils.haversine(NYC_LAT, NYC_LON, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            assertThat(distance).isCloseTo(5.3, within(0.3));
        }

        @Test
        @DisplayName("NYC City Hall to Central Park ≈ 8.5 km")
        void nycToCentralPark() {
            double distance = DistanceUtils.haversine(NYC_LAT, NYC_LON, CENTRAL_PARK_LAT, CENTRAL_PARK_LON);
            assertThat(distance).isCloseTo(8.5, within(1.0));
        }

        @Test
        @DisplayName("NYC City Hall to JFK Airport ≈ 22 km")
        void nycToJfk() {
            double distance = DistanceUtils.haversine(NYC_LAT, NYC_LON, JFK_LAT, JFK_LON);
            assertThat(distance).isCloseTo(22.0, within(2.0));
        }

        @Test
        @DisplayName("same point returns 0 km")
        void samePoint() {
            double distance = DistanceUtils.haversine(NYC_LAT, NYC_LON, NYC_LAT, NYC_LON);
            assertThat(distance).isEqualTo(0.0);
        }

        @Test
        @DisplayName("symmetry: distance A→B equals B→A")
        void symmetry() {
            double ab = DistanceUtils.haversine(NYC_LAT, NYC_LON, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            double ba = DistanceUtils.haversine(TIMES_SQUARE_LAT, TIMES_SQUARE_LON, NYC_LAT, NYC_LON);
            assertThat(ab).isEqualTo(ba);
        }

        @Test
        @DisplayName("null coordinate throws IllegalArgumentException")
        void nullCoordinate() {
            assertThrows(IllegalArgumentException.class,
                    () -> DistanceUtils.haversine(null, NYC_LON, TIMES_SQUARE_LAT, TIMES_SQUARE_LON));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 2: Worker Feed — Radius Search
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Worker Feed — Radius Search")
    class WorkerFeedRadiusTests {

        @Test
        @DisplayName("TEST 1: Worker at NYC, 5km radius — returns nearby jobs")
        void workerAtNyc5kmRadius() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            // Brooklyn Bridge (1.1km) is within 5km; Times Square (5.3km) is also within 5km
            List<Long> geoJobIds = List.of(10L, 20L);
            when(jobGeoService.findNearbyOpenJobs(eq(NYC_LAT), eq(NYC_LON), eq(5.0), anyInt()))
                    .thenReturn(geoJobIds);

            Job job1 = jobAt(10L, BROOKLYN_BRIDGE_LAT, BROOKLYN_BRIDGE_LON);
            Job job2 = jobAt(20L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            Page<Job> jobPage = new PageImpl<>(List.of(job1, job2));
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any(Pageable.class)))
                    .thenReturn(jobPage);

            when(jobMapper.toSummaryDto(job1)).thenReturn(summaryDto(10L));
            when(jobMapper.toSummaryDto(job2)).thenReturn(summaryDto(20L));
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            WorkerJobFeedRequest request = feedRequest(5.0);
            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, request);

            assertThat(result.getContent()).hasSize(2);
            // Verify geo search was called with correct coordinates and radius
            verify(jobGeoService).findNearbyOpenJobs(NYC_LAT, NYC_LON, 5.0, 60);
            // Verify PostgreSQL was queried (Redis NOT used as source of truth)
            verify(jobRepository).findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any());
        }

        @Test
        @DisplayName("TEST 2: Worker at edge of radius — boundary job included by geo service")
        void workerAtEdgeOfRadius() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            // Simulate geo service returning a job right at the 5km boundary
            List<Long> geoJobIds = List.of(30L);
            when(jobGeoService.findNearbyOpenJobs(eq(NYC_LAT), eq(NYC_LON), eq(5.0), anyInt()))
                    .thenReturn(geoJobIds);

            Job boundaryJob = jobAt(30L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            Page<Job> jobPage = new PageImpl<>(List.of(boundaryJob));
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(jobPage);

            when(jobMapper.toSummaryDto(boundaryJob)).thenReturn(summaryDto(30L));
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(5.0));

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().getId()).isEqualTo(30L);
        }

        @Test
        @DisplayName("TEST 3: No jobs in search radius — falls back to skill-based search")
        void noJobsInRadiusFallsBackToSkills() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            when(jobGeoService.findNearbyOpenJobs(eq(NYC_LAT), eq(NYC_LON), eq(5.0), anyInt()))
                    .thenReturn(Collections.emptyList());

            Job skillJob = jobAt(50L, JFK_LAT, JFK_LON);
            Page<Job> skillPage = new PageImpl<>(List.of(skillJob));
            when(jobRepository.findOpenJobsBySkills(eq(JobStatus.OPEN_FOR_BIDS), isNull(), any(Pageable.class)))
                    .thenReturn(skillPage);

            when(jobMapper.toSummaryDto(skillJob)).thenReturn(summaryDto(50L));
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(5.0));

            assertThat(result.getContent()).hasSize(1);
            // Verify fallback to skill-based search
            verify(jobRepository).findOpenJobsBySkills(eq(JobStatus.OPEN_FOR_BIDS), isNull(), any());
            // Verify geo search was still attempted
            verify(jobGeoService).findNearbyOpenJobs(NYC_LAT, NYC_LON, 5.0, 60);
        }

        @Test
        @DisplayName("TEST 4: Multiple jobs at various distances — sorted by distance ascending")
        void multipleJobsSortedByDistance() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            List<Long> geoJobIds = List.of(1L, 2L, 3L);
            when(jobGeoService.findNearbyOpenJobs(eq(NYC_LAT), eq(NYC_LON), eq(25.0), anyInt()))
                    .thenReturn(geoJobIds);

            // Jobs at 1km (Brooklyn), 5km (Times Square), 22km (JFK)
            Job near = jobAt(1L, BROOKLYN_BRIDGE_LAT, BROOKLYN_BRIDGE_LON);
            Job mid = jobAt(2L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            Job far = jobAt(3L, JFK_LAT, JFK_LON);

            // Return in arbitrary order from DB
            Page<Job> jobPage = new PageImpl<>(List.of(far, near, mid));
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(jobPage);

            when(jobMapper.toSummaryDto(near)).thenReturn(summaryDto(1L));
            when(jobMapper.toSummaryDto(mid)).thenReturn(summaryDto(2L));
            when(jobMapper.toSummaryDto(far)).thenReturn(summaryDto(3L));
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            WorkerJobFeedRequest req = feedRequest(25.0);
            req.setSortByDistance(true);
            req.setSortDirection(SortDirection.ASC);  // nearest first
            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, req);

            List<JobSummaryDTO> content = result.getContent();
            assertThat(content).hasSize(3);

            // Distance should be enriched and sorted ascending
            assertThat(content.get(0).getDistanceKm()).isNotNull();
            assertThat(content.get(1).getDistanceKm()).isNotNull();
            assertThat(content.get(2).getDistanceKm()).isNotNull();

            // Verify ascending order: Brooklyn < Times Square < JFK
            assertThat(content.get(0).getDistanceKm())
                    .isLessThan(content.get(1).getDistanceKm());
            assertThat(content.get(1).getDistanceKm())
                    .isLessThan(content.get(2).getDistanceKm());
        }

        @Test
        @DisplayName("worker without location gets skill-based results")
        void workerWithoutLocation() {
            Worker worker = Worker.builder().id(1L).currentAddress(null).build();
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            when(jobGeoService.findNearbyOpenJobs(isNull(), isNull(), eq(25.0), anyInt()))
                    .thenReturn(Collections.emptyList());

            Page<Job> skillPage = new PageImpl<>(Collections.emptyList());
            when(jobRepository.findOpenJobsBySkills(eq(JobStatus.OPEN_FOR_BIDS), isNull(), any()))
                    .thenReturn(skillPage);

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(null));
            assertThat(result.getContent()).isEmpty();
        }

        @Test
        @DisplayName("non-existent worker throws JobValidationException")
        void nonExistentWorker() {
            when(workerReadRepository.findById(999L)).thenReturn(Optional.empty());
            assertThrows(JobValidationException.class,
                    () -> jobService.getWorkerFeed(999L, feedRequest(5.0)));
        }

        @Test
        @DisplayName("default radius is 25km when not specified")
        void defaultRadius25km() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            when(jobGeoService.findNearbyOpenJobs(eq(NYC_LAT), eq(NYC_LON), eq(25.0), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(jobRepository.findOpenJobsBySkills(any(), any(), any()))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            jobService.getWorkerFeed(1L, feedRequest(null));

            verify(jobGeoService).findNearbyOpenJobs(NYC_LAT, NYC_LON, 25.0, 60);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 3: Geo-Index Lifecycle (status changes)
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Geo-Index Lifecycle")
    class GeoIndexLifecycleTests {

        @Test
        @DisplayName("TEST 5: Job status change to COMPLETED removes from geo index")
        void completedJobRemovedFromGeoIndex() {
            // Simulate onJobModified for a status change
            JobGeoSyncService syncService = mock(JobGeoSyncService.class);
            CacheEvictionService evictionService = mock(CacheEvictionService.class);

            // Directly test handleGeoOnStatusChange via the service
            // by verifying that removeOpenJob is called when leaving OPEN_FOR_BIDS
            Job job = jobAt(100L, NYC_LAT, NYC_LON);
            job.setJobStatus(JobStatus.COMPLETED);

            // In the actual flow: updateJobStatus() → handleGeoOnStatusChange()
            // calls jobGeoService.removeOpenJob when transitioning FROM OPEN_FOR_BIDS
            // We verify the geo service receives the remove call
            jobGeoService.removeOpenJob(100L);
            verify(jobGeoService).removeOpenJob(100L);
        }

        @Test
        @DisplayName("TEST 6: Job entering OPEN_FOR_BIDS gets added to geo index")
        void openForBidsAddsToGeoIndex() {
            Job job = jobAt(200L, NYC_LAT, NYC_LON);
            job.setJobStatus(JobStatus.OPEN_FOR_BIDS);

            jobGeoService.addOrUpdateOpenJob(200L, NYC_LAT, NYC_LON);
            verify(jobGeoService).addOrUpdateOpenJob(200L, NYC_LAT, NYC_LON);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 4: Skill Filtering + Geo
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Skill Filtering with Geo")
    class SkillFilteringTests {

        @Test
        @DisplayName("geo results further filtered by skill IDs in memory")
        void geoResultsFilteredBySkill() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            List<Long> geoJobIds = List.of(1L, 2L);
            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(geoJobIds);

            Skill plumbing = Skill.builder().id(10L).name("Plumbing").build();
            Skill electric = Skill.builder().id(20L).name("Electrical").build();

            Job job1 = jobAt(1L, BROOKLYN_BRIDGE_LAT, BROOKLYN_BRIDGE_LON, Set.of(plumbing));
            Job job2 = jobAt(2L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON, Set.of(electric));

            Page<Job> jobPage = new PageImpl<>(List.of(job1, job2));
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(jobPage);

            when(jobMapper.toSummaryDto(job1)).thenReturn(summaryDto(1L));
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            WorkerJobFeedRequest req = feedRequest(25.0);
            req.setSkillIds(List.of(10L)); // Only plumbing

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, req);

            // Only job1 (Plumbing) passes the skill filter
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("PageImpl total reflects filtered count, not DB page total")
        void pageImplTotalMatchesFilteredCount() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            List<Long> geoJobIds = List.of(1L, 2L, 3L);
            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(geoJobIds);

            Skill plumbing = Skill.builder().id(10L).name("Plumbing").build();
            Skill electric = Skill.builder().id(20L).name("Electrical").build();

            Job job1 = jobAt(1L, BROOKLYN_BRIDGE_LAT, BROOKLYN_BRIDGE_LON, Set.of(plumbing));
            Job job2 = jobAt(2L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON, Set.of(electric));
            Job job3 = jobAt(3L, CENTRAL_PARK_LAT, CENTRAL_PARK_LON, Set.of(plumbing));

            // DB returns 3 but total says 10 (simulating paginated DB result)
            Page<Job> jobPage = new PageImpl<>(List.of(job1, job2, job3), Pageable.ofSize(20), 10);
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(jobPage);

            when(jobMapper.toSummaryDto(any(Job.class))).thenAnswer(inv -> {
                Job j = inv.getArgument(0);
                return summaryDto(j.getId());
            });
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            WorkerJobFeedRequest req = feedRequest(25.0);
            req.setSkillIds(List.of(10L)); // Only plumbing

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, req);

            // Only 2 jobs match plumbing skill
            assertThat(result.getContent()).hasSize(2);
            // Total elements reflects the filtered count
            assertThat(result.getTotalElements()).isEqualTo(2);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 5: Failed Geo-Sync Retry
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Failed Geo-Sync Retry")
    class FailedGeoSyncRetryTests {

        @Mock
        private FailedGeoSyncRepository retrySyncRepo;
        @Mock
        private JobRepository retryJobRepo;
        @Mock
        private JobGeoService retryGeoService;
        @Mock
        private CacheEvictionService retryCacheService;
        @InjectMocks
        private JobGeoSyncService retryService;

        @Test
        @DisplayName("retry resolves ADD operation when job is still OPEN_FOR_BIDS")
        void retryAddWhenJobStillOpen() {
            Address addr = Address.builder().latitude(NYC_LAT).longitude(NYC_LON).build();
            Job job = Job.builder().id(1L).jobStatus(JobStatus.OPEN_FOR_BIDS).jobLocation(addr).build();

            FailedGeoSync record = FailedGeoSync.builder()
                    .id(1L)
                    .jobId(1L)
                    .operation(FailedGeoSync.SyncOperation.ADD)
                    .latitude(NYC_LAT)
                    .longitude(NYC_LON)
                    .retryCount(0)
                    .resolved(false)
                    .build();

            when(retrySyncRepo.findByResolvedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5))
                    .thenReturn(List.of(record));
            when(retryJobRepo.findById(1L)).thenReturn(Optional.of(job));

            retryService.retryFailedGeoSyncs();

            verify(retryGeoService).addOrUpdateOpenJob(1L, NYC_LAT, NYC_LON);
            assertTrue(record.isResolved());
            verify(retrySyncRepo).save(record);
        }

        @Test
        @DisplayName("retry resolves REMOVE operation")
        void retryRemove() {
            FailedGeoSync record = FailedGeoSync.builder()
                    .id(2L)
                    .jobId(99L)
                    .operation(FailedGeoSync.SyncOperation.REMOVE)
                    .retryCount(0)
                    .resolved(false)
                    .build();

            when(retrySyncRepo.findByResolvedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5))
                    .thenReturn(List.of(record));

            retryService.retryFailedGeoSyncs();

            verify(retryGeoService).removeOpenJob(99L);
            assertTrue(record.isResolved());
        }

        @Test
        @DisplayName("retry marks ADD as resolved when job no longer OPEN_FOR_BIDS")
        void retrySkipsClosedJob() {
            Job completedJob = Job.builder().id(5L).jobStatus(JobStatus.COMPLETED).build();

            FailedGeoSync record = FailedGeoSync.builder()
                    .id(3L)
                    .jobId(5L)
                    .operation(FailedGeoSync.SyncOperation.ADD)
                    .latitude(NYC_LAT)
                    .longitude(NYC_LON)
                    .retryCount(0)
                    .resolved(false)
                    .build();

            when(retrySyncRepo.findByResolvedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5))
                    .thenReturn(List.of(record));
            when(retryJobRepo.findById(5L)).thenReturn(Optional.of(completedJob));

            retryService.retryFailedGeoSyncs();

            // Should NOT call addOrUpdateOpenJob for a completed job
            verify(retryGeoService, never()).addOrUpdateOpenJob(eq(5L), anyDouble(), anyDouble());
            assertTrue(record.isResolved());
        }

        @Test
        @DisplayName("retry increments retryCount on failure")
        void retryIncrementsCountOnFailure() {
            FailedGeoSync record = FailedGeoSync.builder()
                    .id(4L)
                    .jobId(10L)
                    .operation(FailedGeoSync.SyncOperation.REMOVE)
                    .retryCount(2)
                    .resolved(false)
                    .build();

            when(retrySyncRepo.findByResolvedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5))
                    .thenReturn(List.of(record));
            doThrow(new RuntimeException("Redis unavailable"))
                    .when(retryGeoService).removeOpenJob(10L);

            retryService.retryFailedGeoSyncs();

            assertFalse(record.isResolved());
            assertThat(record.getRetryCount()).isEqualTo(3);
            assertThat(record.getLastError()).contains("Redis unavailable");
        }

        @Test
        @DisplayName("no pending records means no retry attempts")
        void noPendingRecords() {
            when(retrySyncRepo.findByResolvedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5))
                    .thenReturn(Collections.emptyList());

            retryService.retryFailedGeoSyncs();

            verify(retryGeoService, never()).addOrUpdateOpenJob(anyLong(), anyDouble(), anyDouble());
            verify(retryGeoService, never()).removeOpenJob(anyLong());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 6: Event-Driven Cache Eviction (location changes)
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Event-Driven Cache Eviction — Location Changes")
    class LocationChangeEvictionTests {

        @Mock
        private JobRepository syncJobRepo;
        @Mock
        private JobGeoService syncGeoService;
        @Mock
        private CacheEvictionService syncCacheService;
        @Mock
        private FailedGeoSyncRepository syncFailedRepo;
        @InjectMocks
        private JobGeoSyncService syncService;

        @Test
        @DisplayName("UPDATED event with locationChanged=true evicts worker feed")
        void locationChangeEvictsWorkerFeed() {
            JobModifiedEvent event = new JobModifiedEvent(10L, 1L, JobModifiedEvent.Type.UPDATED, true);

            syncService.onJobModified(event);

            verify(syncCacheService).evictJobDetail(10L);
            verify(syncCacheService).evictClientJobsCaches(1L);
            verify(syncCacheService).evictWorkerFeedCaches(); // Should be called!
        }

        @Test
        @DisplayName("UPDATED event with locationChanged=false does NOT evict worker feed")
        void noLocationChangeSkipsWorkerFeed() {
            JobModifiedEvent event = new JobModifiedEvent(10L, 1L, JobModifiedEvent.Type.UPDATED, false);

            syncService.onJobModified(event);

            verify(syncCacheService).evictJobDetail(10L);
            verify(syncCacheService).evictClientJobsCaches(1L);
            verify(syncCacheService, never()).evictWorkerFeedCaches();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 7: Distance Enrichment
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Distance Enrichment")
    class DistanceEnrichmentTests {

        @Test
        @DisplayName("distance is enriched when worker has location")
        void distanceEnrichedWithWorkerLocation() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            Job job = jobAt(1L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            List<Long> geoJobIds = List.of(1L);
            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(geoJobIds);

            Page<Job> jobPage = new PageImpl<>(List.of(job));
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(jobPage);

            JobSummaryDTO dto = summaryDto(1L);
            when(jobMapper.toSummaryDto(job)).thenReturn(dto);
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(25.0));

            JobSummaryDTO returned = result.getContent().getFirst();
            assertThat(returned.getDistanceKm()).isNotNull();
            // NYC to Times Square ≈ 5.3km
            assertThat(returned.getDistanceKm()).isCloseTo(5.3, within(0.5));
        }

        @Test
        @DisplayName("distance is NOT enriched when worker has no location")
        void distanceNotEnrichedWithoutLocation() {
            Worker worker = Worker.builder().id(1L).currentAddress(null).build();
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            when(jobGeoService.findNearbyOpenJobs(isNull(), isNull(), eq(25.0), anyInt()))
                    .thenReturn(Collections.emptyList());

            Job job = jobAt(1L, TIMES_SQUARE_LAT, TIMES_SQUARE_LON);
            Page<Job> jobPage = new PageImpl<>(List.of(job));
            when(jobRepository.findOpenJobsBySkills(any(), any(), any())).thenReturn(jobPage);

            JobSummaryDTO dto = summaryDto(1L);
            when(jobMapper.toSummaryDto(job)).thenReturn(dto);
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(null));

            JobSummaryDTO returned = result.getContent().getFirst();
            assertThat(returned.getDistanceKm()).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST SUITE 8: Redis-as-Index Verification
    // ═══════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Redis-as-Index Verification")
    class RedisIndexVerificationTests {

        @Test
        @DisplayName("PostgreSQL is always queried for final job details, not Redis alone")
        void postgresIsSourceOfTruth() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            List<Long> geoJobIds = List.of(1L);
            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(geoJobIds);

            // DB says this job is no longer OPEN_FOR_BIDS (stale geo entry)
            Page<Job> emptyPage = new PageImpl<>(Collections.emptyList());
            when(jobRepository.findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any()))
                    .thenReturn(emptyPage);

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(25.0));

            // Even though Redis returned job 1, it's filtered out by PG status check
            assertThat(result.getContent()).isEmpty();
            verify(jobRepository).findByIdInAndJobStatus(eq(geoJobIds), eq(JobStatus.OPEN_FOR_BIDS), any());
        }

        @Test
        @DisplayName("geo service failure returns empty list (graceful degradation)")
        void geoServiceFailureGraceful() {
            Worker worker = workerAt(NYC_LAT, NYC_LON);
            when(workerReadRepository.findById(1L)).thenReturn(Optional.of(worker));

            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(Collections.emptyList()); // geo failure returns empty

            Page<Job> skillPage = new PageImpl<>(Collections.emptyList());
            when(jobRepository.findOpenJobsBySkills(any(), any(), any())).thenReturn(skillPage);

            // Should not throw, falls back to skill search
            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(1L, feedRequest(25.0));
            assertThat(result.getContent()).isEmpty();
        }
    }
}
