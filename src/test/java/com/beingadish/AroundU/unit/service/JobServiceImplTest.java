package com.beingadish.AroundU.unit.service;

import com.beingadish.AroundU.common.constants.enums.JobStatus;
import com.beingadish.AroundU.job.dto.*;
import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.user.entity.Client;
import com.beingadish.AroundU.user.entity.Worker;
import com.beingadish.AroundU.location.entity.Address;
import com.beingadish.AroundU.common.entity.Skill;
import com.beingadish.AroundU.job.event.JobModifiedEvent;
import com.beingadish.AroundU.job.exception.JobNotFoundException;
import com.beingadish.AroundU.job.exception.JobValidationException;
import com.beingadish.AroundU.job.mapper.JobMapper;
import com.beingadish.AroundU.location.repository.AddressRepository;
import com.beingadish.AroundU.bid.repository.BidRepository;
import com.beingadish.AroundU.user.repository.ClientRepository;
import com.beingadish.AroundU.location.repository.FailedGeoSyncRepository;
import com.beingadish.AroundU.location.entity.FailedGeoSync;
import com.beingadish.AroundU.job.repository.JobRepository;
import com.beingadish.AroundU.common.repository.SkillRepository;
import com.beingadish.AroundU.user.repository.WorkerReadRepository;
import com.beingadish.AroundU.infrastructure.cache.CacheEvictionService;
import com.beingadish.AroundU.location.service.JobGeoService;
import com.beingadish.AroundU.infrastructure.metrics.MetricsService;
import com.beingadish.AroundU.job.service.impl.JobServiceImpl;
import com.beingadish.AroundU.infrastructure.ranking.RankingEngineClient;
import com.beingadish.AroundU.common.service.SkillService;
import com.beingadish.AroundU.user.service.WorkerPenaltyService;
import com.beingadish.AroundU.fixtures.JobTestBuilder;
import com.beingadish.AroundU.fixtures.TestFixtures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import com.beingadish.AroundU.common.util.PageResponse;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobServiceImpl")
class JobServiceImplTest {

    @Mock
    private JobRepository jobRepository;
    @Mock
    private ClientRepository clientRepository;
    @Mock
    private AddressRepository addressRepository;
    @Mock
    private SkillRepository skillRepository;
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
    @Mock
    private SkillService skillService;
    @Mock
    private WorkerPenaltyService workerPenaltyService;
    @Mock
    private RankingEngineClient rankingEngineClient;

    @InjectMocks
    private JobServiceImpl jobService;

    // Shared fixtures
    private Client client;
    private Worker worker;
    private Address address;
    private Set<Skill> skills;
    private Job job;
    private JobDetailDTO jobDetailDTO;

    @BeforeEach
    void setUp() {
        client = TestFixtures.client();
        worker = TestFixtures.worker();
        address = TestFixtures.address();
        skills = TestFixtures.plumbingSkills();
        job = TestFixtures.job();
        jobDetailDTO = TestFixtures.jobDetailDTO();

        // Stub metrics so recordTimer executes the supplier immediately
        Timer timer = mock(Timer.class);
        Counter counter = mock(Counter.class);
        lenient().when(metricsService.getJobCreationTimer()).thenReturn(timer);
        lenient().when(metricsService.getJobsCreatedCounter()).thenReturn(counter);
        lenient().when(metricsService.getJobsCompletedCounter()).thenReturn(counter);
        lenient().when(metricsService.getJobsCancelledCounter()).thenReturn(counter);
        lenient().when(metricsService.recordTimer(any(Timer.class), any())).thenAnswer(inv -> {
            java.util.function.Supplier<?> supplier = inv.getArgument(1);
            return supplier.get();
        });
    }

    // ── Create Job ───────────────────────────────────────────────
    @Nested
    @DisplayName("createJob")
    class CreateJob {

        @Test
        @DisplayName("success – valid input creates job and indexes geo")
        void createJob_Success() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(1L)).thenReturn(Optional.of(address));
            when(skillRepository.findAllById(anyList())).thenReturn(new ArrayList<>(skills));
            when(jobMapper.toEntity(any(), any(), any(), any())).thenReturn(job);
            when(jobRepository.save(any(Job.class))).thenReturn(job);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.createJob(1L, TestFixtures.jobCreateRequest());

            assertNotNull(result);
            assertEquals("Fix plumbing", result.getTitle());
            verify(jobGeoService).addOrUpdateOpenJob(eq(job.getId()), anyDouble(), anyDouble());
            verify(eventPublisher).publishEvent(any(JobModifiedEvent.class));
        }

        @Test
        @DisplayName("client not found throws JobValidationException")
        void createJob_ClientNotFound() {
            when(clientRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(JobValidationException.class,
                    () -> jobService.createJob(999L, TestFixtures.jobCreateRequest()));
        }

        @Test
        @DisplayName("location not found throws JobValidationException")
        void createJob_LocationNotFound() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(anyLong())).thenReturn(Optional.empty());

            assertThrows(JobValidationException.class,
                    () -> jobService.createJob(1L, TestFixtures.jobCreateRequest()));
        }

        @Test
        @DisplayName("empty skills throws JobValidationException")
        void createJob_EmptySkills() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(1L)).thenReturn(Optional.of(address));
            when(skillRepository.findAllById(anyList())).thenReturn(Collections.emptyList());

            assertThrows(JobValidationException.class,
                    () -> jobService.createJob(1L, TestFixtures.jobCreateRequest()));
        }

        @Test
        @DisplayName("publishes JobModifiedEvent on creation")
        void createJob_PublishesEvent() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(1L)).thenReturn(Optional.of(address));
            when(skillRepository.findAllById(anyList())).thenReturn(new ArrayList<>(skills));
            when(jobMapper.toEntity(any(), any(), any(), any())).thenReturn(job);
            when(jobRepository.save(any(Job.class))).thenReturn(job);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);

            jobService.createJob(1L, TestFixtures.jobCreateRequest());

            verify(eventPublisher).publishEvent(argThat((Object event)
                    -> event instanceof JobModifiedEvent e && e.type() == JobModifiedEvent.Type.CREATED
            ));
        }

        @Test
        @DisplayName("adds to geo index when status is OPEN_FOR_BIDS")
        void createJob_AddToGeoIndex() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(1L)).thenReturn(Optional.of(address));
            when(skillRepository.findAllById(anyList())).thenReturn(new ArrayList<>(skills));
            when(jobMapper.toEntity(any(), any(), any(), any())).thenReturn(job);
            when(jobRepository.save(any(Job.class))).thenReturn(job);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);

            jobService.createJob(1L, TestFixtures.jobCreateRequest());

            verify(jobGeoService).addOrUpdateOpenJob(
                    eq(job.getId()),
                    eq(address.getLatitude()),
                    eq(address.getLongitude())
            );
        }

        @Test
        @DisplayName("geo failure does not prevent job creation")
        void createJob_GeoFailureDoesNotRollBack() {
            when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
            when(addressRepository.findById(1L)).thenReturn(Optional.of(address));
            when(skillRepository.findAllById(anyList())).thenReturn(new ArrayList<>(skills));
            when(jobMapper.toEntity(any(), any(), any(), any())).thenReturn(job);
            when(jobRepository.save(any(Job.class))).thenReturn(job);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);
            doThrow(new RuntimeException("Redis down")).when(jobGeoService)
                    .addOrUpdateOpenJob(anyLong(), anyDouble(), anyDouble());

            JobDetailDTO result = jobService.createJob(1L, TestFixtures.jobCreateRequest());

            assertNotNull(result);
            verify(failedGeoSyncRepository).save(any(FailedGeoSync.class));
        }
    }

    // ── Update Job ───────────────────────────────────────────────
    @Nested
    @DisplayName("updateJob")
    class UpdateJob {

        @Test
        @DisplayName("only the owning client can update")
        void updateJob_OnlyClientCanUpdate() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));

            assertThrows(AccessDeniedException.class,
                    () -> jobService.updateJob(100L, 999L, TestFixtures.jobUpdateRequest()));
        }

        @Test
        @DisplayName("job not found throws exception")
        void updateJob_JobNotFound() {
            when(jobRepository.findById(100L)).thenReturn(Optional.empty());

            assertThrows(JobNotFoundException.class,
                    () -> jobService.updateJob(100L, 1L, TestFixtures.jobUpdateRequest()));
        }

        @Test
        @DisplayName("successful update returns detail and publishes event")
        void updateJob_Success() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));
            when(jobRepository.save(any(Job.class))).thenReturn(job);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.updateJob(100L, 1L, TestFixtures.jobUpdateRequest());

            assertNotNull(result);
            verify(eventPublisher).publishEvent(any(JobModifiedEvent.class));
        }
    }

    // ── Delete Job ───────────────────────────────────────────────
    @Nested
    @DisplayName("deleteJob")
    class DeleteJob {

        @Test
        @DisplayName("removes from geo index when job was OPEN_FOR_BIDS")
        void deleteJob_CleansUpRedis() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));

            jobService.deleteJob(100L, 1L);

            verify(jobGeoService).removeOpenJob(100L);
            verify(jobRepository).delete(job);
        }

        @Test
        @DisplayName("non-owner cannot delete")
        void deleteJob_NonOwnerDenied() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));

            assertThrows(AccessDeniedException.class,
                    () -> jobService.deleteJob(100L, 999L));
        }

        @Test
        @DisplayName("publishes DELETE event")
        void deleteJob_PublishesEvent() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));

            jobService.deleteJob(100L, 1L);

            verify(eventPublisher).publishEvent(argThat((Object event)
                    -> event instanceof JobModifiedEvent e && e.type() == JobModifiedEvent.Type.DELETED));
        }
    }

    // ── Get Job Detail ───────────────────────────────────────────
    @Nested
    @DisplayName("getJobDetail")
    class GetJobDetail {

        @Test
        @DisplayName("returns DTO for existing job")
        void getJobDetail_Success() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));
            when(jobMapper.toDetailDto(job)).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.getJobDetail(100L);

            assertNotNull(result);
            assertEquals(100L, result.getId());
        }

        @Test
        @DisplayName("throws when job not found")
        void getJobDetail_NotFound() {
            when(jobRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(JobNotFoundException.class,
                    () -> jobService.getJobDetail(999L));
        }
    }

    // ── Update Job Status ────────────────────────────────────────
    @Nested
    @DisplayName("updateJobStatus")
    class UpdateJobStatus {

        @Test
        @DisplayName("valid status transition succeeds")
        void updateJobStatus_Success() {
            Job openJob = JobTestBuilder.aJob().withId(100L).withStatus(JobStatus.OPEN_FOR_BIDS).build();
            when(jobRepository.findByIdAndCreatedById(100L, 1L)).thenReturn(Optional.of(openJob));
            when(jobRepository.save(any(Job.class))).thenReturn(openJob);
            when(jobMapper.toDetailDto(any(Job.class))).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.updateJobStatus(100L, 1L,
                    TestFixtures.statusUpdateRequest(JobStatus.BID_SELECTED_AWAITING_HANDSHAKE));

            assertNotNull(result);
        }

        @Test
        @DisplayName("invalid transition throws JobValidationException")
        void updateJobStatus_InvalidTransition() {
            Job completedJob = JobTestBuilder.aJob().withId(100L).withStatus(JobStatus.COMPLETED).build();
            when(jobRepository.findByIdAndCreatedById(100L, 1L)).thenReturn(Optional.of(completedJob));

            assertThrows(JobValidationException.class,
                    () -> jobService.updateJobStatus(100L, 1L,
                            TestFixtures.statusUpdateRequest(JobStatus.IN_PROGRESS)));
        }

        @Test
        @DisplayName("same status transition throws")
        void updateJobStatus_SameStatus() {
            Job openJob = JobTestBuilder.aJob().withId(100L).withStatus(JobStatus.OPEN_FOR_BIDS).build();
            when(jobRepository.findByIdAndCreatedById(100L, 1L)).thenReturn(Optional.of(openJob));

            assertThrows(JobValidationException.class,
                    () -> jobService.updateJobStatus(100L, 1L,
                            TestFixtures.statusUpdateRequest(JobStatus.OPEN_FOR_BIDS)));
        }
    }

    // ── Worker Feed ──────────────────────────────────────────────
    @Nested
    @DisplayName("getWorkerFeed")
    class WorkerFeed {

        @Test
        @DisplayName("returns jobs within geo radius")
        void getWorkerFeed_WithinGeoRadius() {
            when(workerReadRepository.findById(10L)).thenReturn(Optional.of(worker));
            when(jobGeoService.findNearbyOpenJobs(anyDouble(), anyDouble(), anyDouble(), anyInt()))
                    .thenReturn(List.of(100L));
            when(jobRepository.findByIdInAndJobStatus(anyCollection(), eq(JobStatus.OPEN_FOR_BIDS), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(job)));
            when(jobMapper.toSummaryDto(any(Job.class))).thenReturn(new JobSummaryDTO());
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(10L, TestFixtures.workerFeedRequest());

            assertNotNull(result);
            assertFalse(result.isEmpty());
        }

        @Test
        @DisplayName("falls back to skill search when no geo results")
        void getWorkerFeed_NoGeoResults_UseFallback() {
            when(workerReadRepository.findById(10L)).thenReturn(Optional.of(worker));
            when(jobGeoService.findNearbyOpenJobs(any(), any(), anyDouble(), anyInt()))
                    .thenReturn(Collections.emptyList());
            when(jobRepository.findOpenJobsBySkills(eq(JobStatus.OPEN_FOR_BIDS), anyCollection(), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(job)));
            when(jobMapper.toSummaryDto(any(Job.class))).thenReturn(new JobSummaryDTO());
            when(bidRepository.countByJobIds(anyList())).thenReturn(Collections.emptyList());

            PageResponse<JobSummaryDTO> result = jobService.getWorkerFeed(10L, TestFixtures.workerFeedRequest());

            assertFalse(result.isEmpty());
            verify(jobRepository).findOpenJobsBySkills(eq(JobStatus.OPEN_FOR_BIDS), anyCollection(), any());
        }

        @Test
        @DisplayName("worker not found throws exception")
        void getWorkerFeed_WorkerNotFound() {
            when(workerReadRepository.findById(999L)).thenReturn(Optional.empty());

            assertThrows(JobValidationException.class,
                    () -> jobService.getWorkerFeed(999L, TestFixtures.workerFeedRequest()));
        }
    }

    // ── List Jobs ────────────────────────────────────────────────
    @Nested
    @DisplayName("listJobs")
    class ListJobs {

        @Test
        @DisplayName("searches by location without skills")
        void listJobs_ByLocation() {
            when(jobRepository.searchByLocation("New York", "Manhattan")).thenReturn(List.of(job));
            when(jobMapper.toSummaryList(anyList())).thenReturn(List.of(new JobSummaryDTO()));

            List<JobSummaryDTO> result = jobService.listJobs("New York", "Manhattan", null);

            assertFalse(result.isEmpty());
            verify(jobRepository).searchByLocation("New York", "Manhattan");
        }

        @Test
        @DisplayName("searches by location and skills")
        void listJobs_ByLocationAndSkills() {
            when(jobRepository.searchByLocationAndSkills("New York", "Manhattan", List.of(1L)))
                    .thenReturn(List.of(job));
            when(jobMapper.toSummaryList(anyList())).thenReturn(List.of(new JobSummaryDTO()));

            List<JobSummaryDTO> result = jobService.listJobs("New York", "Manhattan", List.of(1L));

            assertFalse(result.isEmpty());
            verify(jobRepository).searchByLocationAndSkills("New York", "Manhattan", List.of(1L));
        }
    }

    // ── Get Job For Worker ───────────────────────────────────────
    @Nested
    @DisplayName("getJobForWorker")
    class GetJobForWorker {

        @Test
        @DisplayName("open jobs are visible to any worker")
        void getJobForWorker_OpenJob() {
            when(jobRepository.findById(100L)).thenReturn(Optional.of(job));
            when(jobMapper.toDetailDto(job)).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.getJobForWorker(100L, 10L);

            assertNotNull(result);
        }

        @Test
        @DisplayName("assigned worker can view non-open job")
        void getJobForWorker_AssignedWorker() {
            Job assigned = TestFixtures.assignedJob(100L, client, worker);
            when(jobRepository.findById(100L)).thenReturn(Optional.of(assigned));
            when(jobMapper.toDetailDto(assigned)).thenReturn(jobDetailDTO);

            JobDetailDTO result = jobService.getJobForWorker(100L, 10L);

            assertNotNull(result);
        }

        @Test
        @DisplayName("non-assigned worker cannot view non-open job")
        void getJobForWorker_AccessDenied() {
            Job inProgress = JobTestBuilder.aJob().withId(100L)
                    .withStatus(JobStatus.IN_PROGRESS)
                    .withWorker(TestFixtures.worker(99L))
                    .build();
            when(jobRepository.findById(100L)).thenReturn(Optional.of(inProgress));

            assertThrows(AccessDeniedException.class,
                    () -> jobService.getJobForWorker(100L, 10L));
        }
    }
}
