package com.beingadish.AroundU.job.service.impl;

import com.beingadish.AroundU.infrastructure.config.RedisConfig;
import com.beingadish.AroundU.common.constants.enums.JobStatus;
import com.beingadish.AroundU.common.constants.enums.SortDirection;
import com.beingadish.AroundU.job.dto.JobCreateRequest;
import com.beingadish.AroundU.job.dto.JobDetailDTO;
import com.beingadish.AroundU.job.dto.JobFilterRequest;
import com.beingadish.AroundU.job.dto.JobStatusUpdateRequest;
import com.beingadish.AroundU.job.dto.JobSummaryDTO;
import com.beingadish.AroundU.job.dto.JobUpdateRequest;
import com.beingadish.AroundU.job.dto.WorkerJobFeedRequest;
import com.beingadish.AroundU.common.util.PageResponse;
import com.beingadish.AroundU.location.entity.Address;
import com.beingadish.AroundU.user.entity.Client;
import com.beingadish.AroundU.location.entity.FailedGeoSync;
import com.beingadish.AroundU.job.entity.Job;
import com.beingadish.AroundU.common.entity.Skill;
import com.beingadish.AroundU.user.entity.Worker;
import com.beingadish.AroundU.job.event.JobModifiedEvent;
import com.beingadish.AroundU.job.exception.JobNotFoundException;
import com.beingadish.AroundU.job.exception.JobValidationException;
import com.beingadish.AroundU.job.mapper.JobMapper;
import com.beingadish.AroundU.location.repository.AddressRepository;
import com.beingadish.AroundU.bid.repository.BidRepository;
import com.beingadish.AroundU.user.repository.ClientRepository;
import com.beingadish.AroundU.location.repository.FailedGeoSyncRepository;
import com.beingadish.AroundU.job.repository.JobRepository;
import com.beingadish.AroundU.common.repository.SkillRepository;
import com.beingadish.AroundU.common.service.SkillService;
import com.beingadish.AroundU.user.repository.WorkerReadRepository;
import com.beingadish.AroundU.infrastructure.cache.CacheEvictionService;
import com.beingadish.AroundU.location.service.JobGeoService;
import com.beingadish.AroundU.job.service.JobService;
import com.beingadish.AroundU.infrastructure.metrics.MetricsService;
import com.beingadish.AroundU.common.util.DistanceUtils;
import com.beingadish.AroundU.common.util.PopularityUtils;
import com.beingadish.AroundU.common.util.SortValidator;
import com.beingadish.AroundU.user.service.WorkerPenaltyService;
import com.beingadish.AroundU.infrastructure.ranking.RankingEngineClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class JobServiceImpl implements JobService {

    private static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.CREATED,
            JobStatus.OPEN_FOR_BIDS,
            JobStatus.BID_SELECTED_AWAITING_HANDSHAKE,
            JobStatus.READY_TO_START,
            JobStatus.IN_PROGRESS,
            JobStatus.COMPLETED_PENDING_PAYMENT
    );

    private static final List<JobStatus> PAST_STATUSES = List.of(
            JobStatus.COMPLETED, JobStatus.CANCELLED, JobStatus.PAYMENT_RELEASED);

    private final JobRepository jobRepository;
    private final ClientRepository clientRepository;
    private final AddressRepository addressRepository;
    private final SkillRepository skillRepository;
    private final SkillService skillService;
    private final WorkerReadRepository workerReadRepository;
    private final BidRepository bidRepository;
    private final FailedGeoSyncRepository failedGeoSyncRepository;
    private final JobMapper jobMapper;
    private final JobGeoService jobGeoService;
    private final MetricsService metricsService;
    private final ApplicationEventPublisher eventPublisher;
    private final CacheEvictionService cacheEvictionService;
    private final WorkerPenaltyService workerPenaltyService;
    private final RankingEngineClient rankingEngineClient;

    @Override
    @Transactional
    public JobDetailDTO createJob(Long clientId, JobCreateRequest request) {
        return metricsService.recordTimer(metricsService.getJobCreationTimer(), () -> {
            Client client = clientRepository.findById(clientId).orElseThrow(() -> new JobValidationException("Client not found"));

            // Single active job enforcement: client cannot create a new job
            // if they already have one IN_PROGRESS
            if (jobRepository.hasJobInStatus(clientId, JobStatus.IN_PROGRESS)) {
                throw new JobValidationException("You already have a job in progress. Complete or cancel it before creating a new one.");
            }

            Address location = addressRepository.findById(request.getJobLocationId()).orElseThrow(() -> new JobValidationException("Job location not found"));

            // Resolve skills: prefer names (auto-create), fall back to IDs
            Set<Skill> skills;
            if (request.getRequiredSkillNames() != null && !request.getRequiredSkillNames().isEmpty()) {
                skills = skillService.findOrCreateSkills(request.getRequiredSkillNames());
            } else if (request.getRequiredSkillIds() != null && !request.getRequiredSkillIds().isEmpty()) {
                skills = new HashSet<>(skillRepository.findAllById(request.getRequiredSkillIds()));
            } else {
                throw new JobValidationException("At least one required skill must be provided (via names or IDs)");
            }
            if (skills.isEmpty()) {
                throw new JobValidationException("At least one valid required skill must be provided");
            }
            Job job = jobMapper.toEntity(request, location, skills, client);
            job.setJobStatus(JobStatus.OPEN_FOR_BIDS);
            Job saved = jobRepository.save(job);
            if (saved.getJobStatus() == JobStatus.OPEN_FOR_BIDS) {
                safeGeoAdd(saved.getId(), location.getLatitude(), location.getLongitude());
            }
            metricsService.getJobsCreatedCounter().increment();
            metricsService.incrementActiveJobs();
            log.info("Created job id={} for client={}", saved.getId(), clientId);
            eventPublisher.publishEvent(new JobModifiedEvent(saved.getId(), clientId, JobModifiedEvent.Type.CREATED, false));
            cacheEvictionService.evictClientJobsCaches(clientId);
            cacheEvictionService.evictWorkerFeedCaches();
            return jobMapper.toDetailDto(saved);
        });
    }

    @Override
    @Transactional
    public JobDetailDTO updateJob(Long jobId, Long clientId, JobUpdateRequest request) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException("Job not found"));
        if (!Objects.equals(job.getCreatedBy().getId(), clientId)) {
            throw new AccessDeniedException("You cannot edit another client's job");
        }
        Address location = null;
        if (request.getJobLocationId() != null) {
            location = addressRepository.findById(request.getJobLocationId()).orElseThrow(() -> new JobValidationException("Job location not found"));
        }
        Set<Skill> skills = null;
        if (request.getRequiredSkillIds() != null) {
            skills = new HashSet<>(skillRepository.findAllById(request.getRequiredSkillIds()));
            if (skills.isEmpty()) {
                throw new JobValidationException("Required skills cannot be empty");
            }
        }
        request.setJobStatus(null); // enforce status changes through updateJobStatus
        jobMapper.updateEntity(request, job, location, skills);
        Job saved = jobRepository.save(job);
        boolean locationChanged = false;
        if (location != null && saved.getJobStatus() == JobStatus.OPEN_FOR_BIDS) {
            safeGeoAdd(saved.getId(), location.getLatitude(), location.getLongitude());
            locationChanged = true;
        }
        eventPublisher.publishEvent(new JobModifiedEvent(jobId, clientId, JobModifiedEvent.Type.UPDATED, locationChanged));
        cacheEvictionService.evictJobDetail(jobId);
        cacheEvictionService.evictClientJobsCaches(clientId);
        if (locationChanged) {
            cacheEvictionService.evictWorkerFeedCaches();
        }
        return jobMapper.toDetailDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisConfig.CACHE_JOB_DETAIL, key = "#jobId")
    public JobDetailDTO getJobDetail(Long jobId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException("Job not found"));
        return jobMapper.toDetailDto(job);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSummaryDTO> listJobs(String city, String area, List<Long> skillIds) {
        List<Job> jobs;
        if (skillIds == null || skillIds.isEmpty()) {
            jobs = jobRepository.searchByLocation(city, area);
        } else {
            jobs = jobRepository.searchByLocationAndSkills(city, area, skillIds);
        }
        return jobMapper.toSummaryList(jobs);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisConfig.CACHE_CLIENT_JOBS, key = "#clientId + ':' + #filterRequest.toCacheKey()")
    public PageResponse<JobSummaryDTO> getClientJobs(Long clientId, JobFilterRequest filterRequest) {
        // Validate sort fields against whitelist
        SortValidator.validate(filterRequest.getSortBy(), filterRequest.getSortDirection(), SortValidator.JOB_FIELDS);
        if (filterRequest.getSecondarySortBy() != null) {
            SortValidator.validate(filterRequest.getSecondarySortBy(), filterRequest.getSecondarySortDirection(), SortValidator.JOB_FIELDS);
        }

        int page = Optional.ofNullable(filterRequest.getPage()).orElse(0);
        int size = Optional.ofNullable(filterRequest.getSize()).orElse(20);

        // Build sort — virtual fields (distance, bidCount) yield Sort.unsorted()
        Sort sort = SortValidator.buildMultiSort(
                filterRequest.getSortBy(), filterRequest.getSortDirection(),
                filterRequest.getSecondarySortBy(), filterRequest.getSecondarySortDirection(),
                SortValidator.JOB_FIELDS);

        Pageable pageable = PageRequest.of(page, size, sort);
        List<JobStatus> statuses = (filterRequest.getStatuses() == null || filterRequest.getStatuses().isEmpty()) ? ACTIVE_STATUSES : filterRequest.getStatuses();

        Page<Job> jobPage;
        if (filterRequest.getStartDate() != null && filterRequest.getEndDate() != null) {
            LocalDate start = filterRequest.getStartDate();
            LocalDate end = filterRequest.getEndDate();
            LocalDateTime startDateTime = start.atStartOfDay();
            LocalDateTime endDateTime = end.atTime(LocalTime.MAX);
            jobPage = jobRepository.findByCreatedByIdAndJobStatusInAndCreatedAtBetween(clientId, statuses, startDateTime, endDateTime, pageable);
        } else {
            jobPage = jobRepository.findByCreatedByIdAndJobStatusIn(clientId, statuses, pageable);
        }

        // Apply virtual field sorting (distance / popularity) in-memory
        boolean distanceSorting = Boolean.TRUE.equals(filterRequest.getSortByDistance())
                || "distance".equalsIgnoreCase(filterRequest.getSortBy());
        boolean popularitySorting = "bidcount".equalsIgnoreCase(filterRequest.getSortBy())
                || "viewcount".equalsIgnoreCase(filterRequest.getSortBy());

        if (distanceSorting || popularitySorting) {
            Map<Long, Long> bidCountsMap = fetchBidCounts(jobPage.getContent());
            List<JobSummaryDTO> dtos = jobPage.getContent().stream()
                    .map(job -> {
                        JobSummaryDTO dto = jobMapper.toSummaryDto(job);
                        enrichWithDistance(dto, job, filterRequest.getDistanceLatitude(), filterRequest.getDistanceLongitude());
                        enrichWithPopularity(dto, job, bidCountsMap);
                        return dto;
                    })
                    .sorted(buildVirtualComparator(filterRequest.getSortBy(), filterRequest.getSortDirection(), distanceSorting))
                    .toList();
            return new PageResponse<>(new PageImpl<>(dtos, pageable, jobPage.getTotalElements()));
        }

        return new PageResponse<>(jobPage.map(jobMapper::toSummaryDto));
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisConfig.CACHE_CLIENT_JOBS, key = "#clientId + ':past:' + #page + ':' + #size")
    public PageResponse<JobSummaryDTO> getClientPastJobs(Long clientId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "updatedAt"));
        Page<Job> jobPage = jobRepository.findByCreatedByIdAndJobStatusIn(clientId, PAST_STATUSES, pageable);
        return new PageResponse<>(jobPage.map(jobMapper::toSummaryDto));
    }

    @Override
    @Transactional(readOnly = true)
    public JobDetailDTO getJobForClient(Long jobId, Long clientId) {
        Job job = jobRepository.findByIdAndCreatedById(jobId, clientId).orElseThrow(() -> new JobNotFoundException("Job not found for client"));
        return jobMapper.toDetailDto(job);
    }

    @Override
    @Transactional
    public JobDetailDTO updateJobStatus(Long jobId, Long clientId, JobStatusUpdateRequest request) {
        Job job = jobRepository.findByIdAndCreatedById(jobId, clientId).orElseThrow(() -> new JobNotFoundException("Job not found for client"));
        validateStatusTransition(job.getJobStatus(), request.getNewStatus());
        JobStatus oldStatus = job.getJobStatus();
        job.setJobStatus(request.getNewStatus());
        Job saved = jobRepository.save(job);
        handleGeoOnStatusChange(saved, oldStatus, request.getNewStatus());
        // Record metrics for terminal statuses
        if (request.getNewStatus() == JobStatus.COMPLETED || request.getNewStatus() == JobStatus.PAYMENT_RELEASED) {
            metricsService.getJobsCompletedCounter().increment();
            metricsService.decrementActiveJobs();
        } else if (request.getNewStatus() == JobStatus.CANCELLED) {
            metricsService.getJobsCancelledCounter().increment();
            metricsService.decrementActiveJobs();
        }
        log.info("Job id={} status updated from {} to {} by client {}", jobId, oldStatus, request.getNewStatus(), clientId);
        eventPublisher.publishEvent(new JobModifiedEvent(jobId, clientId, JobModifiedEvent.Type.STATUS_CHANGED, false));
        cacheEvictionService.evictJobDetail(jobId);
        cacheEvictionService.evictClientJobsCaches(clientId);
        cacheEvictionService.evictWorkerFeedCaches();
        return jobMapper.toDetailDto(saved);
    }

    @Override
    @Transactional
    public JobDetailDTO updateJobStatusByWorker(Long jobId, Long workerId, JobStatusUpdateRequest request) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found"));
        if (job.getAssignedTo() == null || !Objects.equals(job.getAssignedTo().getId(), workerId)) {
            throw new AccessDeniedException("Worker is not assigned to this job");
        }

        // Workers can only transition: READY_TO_START → IN_PROGRESS, IN_PROGRESS → COMPLETED_PENDING_PAYMENT
        JobStatus target = request.getNewStatus();
        if (target != JobStatus.IN_PROGRESS && target != JobStatus.COMPLETED_PENDING_PAYMENT) {
            throw new JobValidationException("Workers can only start tasks or mark them complete");
        }

        validateStatusTransition(job.getJobStatus(), target);
        JobStatus oldStatus = job.getJobStatus();
        job.setJobStatus(target);
        Job saved = jobRepository.save(job);
        handleGeoOnStatusChange(saved, oldStatus, target);

        log.info("Job id={} status updated from {} to {} by worker {}", jobId, oldStatus, target, workerId);
        eventPublisher.publishEvent(new JobModifiedEvent(jobId, job.getCreatedBy().getId(), JobModifiedEvent.Type.STATUS_CHANGED, false));
        cacheEvictionService.evictJobDetail(jobId);
        cacheEvictionService.evictClientJobsCaches(job.getCreatedBy().getId());
        cacheEvictionService.evictWorkerFeedCaches();
        return jobMapper.toDetailDto(saved);
    }

    @Override
    @Transactional
    public JobDetailDTO cancelJobByWorker(Long jobId, Long workerId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException("Job not found"));
        if (job.getAssignedTo() == null || !Objects.equals(job.getAssignedTo().getId(), workerId)) {
            throw new AccessDeniedException("Worker is not assigned to this job");
        }

        // Only allow cancellation from states where worker is actively engaged
        JobStatus current = job.getJobStatus();
        if (current != JobStatus.READY_TO_START && current != JobStatus.IN_PROGRESS
                && current != JobStatus.BID_SELECTED_AWAITING_HANDSHAKE) {
            throw new JobValidationException("Job cannot be cancelled by worker in its current state: " + current);
        }

        // Apply cancellation penalty
        workerPenaltyService.recordCancellation(workerId);

        // Revert job: unassign worker, reopen for bids
        job.setAssignedTo(null);
        job.setJobStatus(JobStatus.OPEN_FOR_BIDS);
        Job saved = jobRepository.save(job);

        // Re-add to geo-index since it's open for bids again
        if (saved.getJobLocation() != null) {
            safeGeoAdd(saved.getId(), saved.getJobLocation().getLatitude(), saved.getJobLocation().getLongitude());
        }

        metricsService.getJobsCancelledCounter().increment();
        log.warn("Worker {} cancelled job {}. Job reverted to OPEN_FOR_BIDS.", workerId, jobId);
        eventPublisher.publishEvent(new JobModifiedEvent(jobId, job.getCreatedBy().getId(), JobModifiedEvent.Type.STATUS_CHANGED, true));
        cacheEvictionService.evictJobDetail(jobId);
        cacheEvictionService.evictClientJobsCaches(job.getCreatedBy().getId());
        cacheEvictionService.evictWorkerFeedCaches();
        return jobMapper.toDetailDto(saved);
    }

    @Override
    @Transactional(readOnly = true)
    @Cacheable(value = RedisConfig.CACHE_WORKER_FEED, key = "#workerId + ':' + #request.toCacheKey()")
    public PageResponse<JobSummaryDTO> getWorkerFeed(Long workerId, WorkerJobFeedRequest request) {
        Worker worker = workerReadRepository.findById(workerId).orElseThrow(() -> new JobValidationException("Worker not found"));

        // Validate sort fields against whitelist
        SortValidator.validate(request.getSortBy(), request.getSortDirection(), SortValidator.JOB_FIELDS);

        double radius = Optional.ofNullable(request.getRadiusKm()).orElse(25.0);
        int page = Optional.ofNullable(request.getPage()).orElse(0);
        int size = Optional.ofNullable(request.getSize()).orElse(20);

        Double workerLat = request.getLatitude();
        Double workerLon = request.getLongitude();

        // Fall back to stored address if request doesn't provide live GPS coordinates
        if (workerLat == null || workerLon == null) {
            workerLat = worker.getCurrentAddress() != null ? worker.getCurrentAddress().getLatitude() : null;
            workerLon = worker.getCurrentAddress() != null ? worker.getCurrentAddress().getLongitude() : null;
        }

        // ── Try Ranking Engine (Rust gRPC) first ─────────────────────
        if (rankingEngineClient.isAvailable()) {
            RankingEngineClient.RankedFeedResult rankedResult = rankingEngineClient.getWorkerFeed(
                    workerId, workerLat, workerLon, radius, page, size, Collections.emptyList());

            if (rankedResult.isAvailable() && !rankedResult.getJobs().isEmpty()) {
                log.debug("Using ranking engine results for worker {} feed ({} jobs)", workerId, rankedResult.getJobs().size());
                Pageable pageable = PageRequest.of(page, size);
                return new PageResponse<>(new PageImpl<>(rankedResult.getJobs(), pageable, rankedResult.getJobs().size()));
            }
            log.debug("Ranking engine returned empty results for worker {}, falling back to local", workerId);
        }

        // ── Fallback: existing local implementation ──────────────────

        final Double finalWorkerLat = workerLat;
        final Double finalWorkerLon = workerLon;

        Sort sort = SortValidator.buildMultiSort(
                request.getSortBy(), request.getSortDirection(),
                request.getSecondarySortBy(), request.getSecondarySortDirection(),
                SortValidator.JOB_FIELDS);

        Pageable pageable = PageRequest.of(page, size, sort);

        List<Long> geoJobIds = jobGeoService.findNearbyOpenJobs(finalWorkerLat, finalWorkerLon, radius, size * 3);

        Page<Job> jobsPage;
        if (!geoJobIds.isEmpty()) {
            jobsPage = jobRepository.findByIdInAndJobStatus(geoJobIds, JobStatus.OPEN_FOR_BIDS, pageable);
        } else {
            jobsPage = jobRepository.findOpenJobsBySkills(JobStatus.OPEN_FOR_BIDS, request.getSkillIds(), pageable);
        }

        List<Job> filtered = new ArrayList<>();
        for (Job job : jobsPage.getContent()) {
            if (request.getSkillIds() == null || request.getSkillIds().isEmpty()) {
                filtered.add(job);
                continue;
            }
            boolean hasSkill = job.getSkillSet().stream().anyMatch(skill -> request.getSkillIds().contains(skill.getId()));
            if (hasSkill) {
                filtered.add(job);
            }
        }

        // Apply virtual sorting (distance / popularity) in-memory
        boolean distanceSorting = Boolean.TRUE.equals(request.getSortByDistance())
                || "distance".equalsIgnoreCase(request.getSortBy());

        Map<Long, Long> bidCountsMap = fetchBidCounts(filtered);
        List<JobSummaryDTO> dtos = filtered.stream()
                .map(job -> {
                    JobSummaryDTO dto = jobMapper.toSummaryDto(job);
                    // Always enrich worker feed with distance when worker location is available
                    if (finalWorkerLat != null && finalWorkerLon != null) {
                        enrichWithDistance(dto, job, finalWorkerLat, finalWorkerLon);
                    }
                    enrichWithPopularity(dto, job, bidCountsMap);
                    return dto;
                })
                .toList();

        if (distanceSorting || "bidcount".equalsIgnoreCase(request.getSortBy())) {
            dtos = dtos.stream()
                    .sorted(buildVirtualComparator(request.getSortBy(), request.getSortDirection(), distanceSorting))
                    .toList();
        }

        // Use the filtered count as total elements, not the DB page total,
        // because in-memory skill filtering may have reduced the result count.
        return new PageResponse<>(new PageImpl<>(dtos, pageable, dtos.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public JobDetailDTO getJobForWorker(Long jobId, Long workerId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException("Job not found"));
        if (job.getJobStatus() == JobStatus.OPEN_FOR_BIDS
                || (job.getAssignedTo() != null && Objects.equals(job.getAssignedTo().getId(), workerId))
                || bidRepository.existsByWorkerIdAndJobId(workerId, jobId)) {
            return jobMapper.toDetailDto(job);
        }
        throw new AccessDeniedException("Worker cannot view this job");
    }

    @Override
    @Transactional(readOnly = true)
    public List<JobSummaryDTO> getWorkerMyJobs(Long workerId, List<String> statuses) {
        Worker worker = workerReadRepository.findById(workerId)
                .orElseThrow(() -> new JobValidationException("Worker not found"));

        List<com.beingadish.AroundU.bid.entity.Bid> bids = bidRepository.findByWorker(worker);

        List<Job> jobs = bids.stream()
                .map(com.beingadish.AroundU.bid.entity.Bid::getJob)
                .distinct()
                .collect(java.util.stream.Collectors.toList());

        if (statuses != null && !statuses.isEmpty()) {
            List<JobStatus> filterStatuses = statuses.stream()
                    .map(s -> {
                        try {
                            return JobStatus.valueOf(s);
                        } catch (IllegalArgumentException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .toList();
            if (!filterStatuses.isEmpty()) {
                jobs = jobs.stream()
                        .filter(j -> filterStatuses.contains(j.getJobStatus()))
                        .toList();
            }
        }

        return jobs.stream()
                .sorted(Comparator.comparing(Job::getCreatedAt).reversed())
                .map(jobMapper::toSummaryDto)
                .toList();
    }

    @Override
    @Transactional
    public void deleteJob(Long jobId, Long clientId) {
        Job job = jobRepository.findById(jobId).orElseThrow(() -> new JobNotFoundException("Job not found"));
        if (!Objects.equals(job.getCreatedBy().getId(), clientId)) {
            throw new AccessDeniedException("You cannot delete another client's job");
        }

        boolean wasOpen = job.getJobStatus() == JobStatus.OPEN_FOR_BIDS;
        jobRepository.delete(job);
        if (wasOpen) {
            safeGeoRemove(jobId);
        }
        eventPublisher.publishEvent(new JobModifiedEvent(jobId, clientId, JobModifiedEvent.Type.DELETED, false));
        cacheEvictionService.evictJobDetail(jobId);
        cacheEvictionService.evictClientJobsCaches(clientId);
        cacheEvictionService.evictWorkerFeedCaches();
        log.info("Deleted job id={} for client={}", jobId, clientId);
    }

    private void validateStatusTransition(JobStatus current, JobStatus target) {
        if (current == target) {
            throw new JobValidationException("Job is already in status " + target);
        }
        boolean allowed = switch (current) {
            case CREATED ->
                target == JobStatus.OPEN_FOR_BIDS;
            case OPEN_FOR_BIDS ->
                target == JobStatus.BID_SELECTED_AWAITING_HANDSHAKE || target == JobStatus.CANCELLED;
            case BID_SELECTED_AWAITING_HANDSHAKE ->
                target == JobStatus.READY_TO_START || target == JobStatus.CANCELLED;
            case READY_TO_START ->
                target == JobStatus.IN_PROGRESS || target == JobStatus.CANCELLED;
            case IN_PROGRESS ->
                target == JobStatus.COMPLETED_PENDING_PAYMENT || target == JobStatus.COMPLETED || target == JobStatus.CANCELLED;
            case COMPLETED_PENDING_PAYMENT ->
                target == JobStatus.PAYMENT_RELEASED || target == JobStatus.COMPLETED;
            case PAYMENT_RELEASED ->
                target == JobStatus.COMPLETED;
            case COMPLETED, CANCELLED, JOB_CLOSED_DUE_TO_EXPIRATION ->
                false;
        };
        if (!allowed) {
            throw new JobValidationException("Invalid status transition from " + current + " to " + target);
        }
    }

    private void handleGeoOnStatusChange(Job job, JobStatus oldStatus, JobStatus newStatus) {
        boolean wasOpen = oldStatus == JobStatus.OPEN_FOR_BIDS;
        boolean nowOpen = newStatus == JobStatus.OPEN_FOR_BIDS;
        if (wasOpen && !nowOpen) {
            safeGeoRemove(job.getId());
        } else if (!wasOpen && nowOpen && job.getJobLocation() != null) {
            safeGeoAdd(job.getId(), job.getJobLocation().getLatitude(), job.getJobLocation().getLongitude());
        }
    }

    // ── Sorting helpers ──────────────────────────────────────────
    /**
     * Enriches a DTO with the distance (in km) from the given reference point.
     */
    private void enrichWithDistance(JobSummaryDTO dto, Job job, Double refLat, Double refLon) {
        if (refLat == null || refLon == null) {
            return;
        }
        Address loc = job.getJobLocation();
        if (loc != null && loc.getLatitude() != null && loc.getLongitude() != null) {
            double km = DistanceUtils.haversine(refLat, refLon, loc.getLatitude(), loc.getLongitude());
            dto.setDistanceKm(Math.round(km * 100.0) / 100.0); // 2 decimal places
        }
    }

    /**
     * Enriches a DTO with a popularity score based on bid count.
     */
    private void enrichWithPopularity(JobSummaryDTO dto, Job job, Map<Long, Long> bidCountsMap) {
        long bidCount = bidCountsMap.getOrDefault(job.getId(), 0L);
        dto.setPopularityScore(PopularityUtils.calculateJobPopularityScore((int) bidCount, null));
    }

    /**
     * Batch-fetches bid counts for a list of jobs.
     */
    private Map<Long, Long> fetchBidCounts(List<Job> jobs) {
        if (jobs.isEmpty()) {
            return Map.of();
        }
        List<Long> jobIds = jobs.stream().map(Job::getId).toList();
        return bidRepository.countByJobIds(jobIds).stream()
                .collect(java.util.stream.Collectors.toMap(
                        row -> (Long) row[0],
                        row -> (Long) row[1]
                ));
    }

    /**
     * Builds a comparator for in-memory (virtual field) sorting of
     * {@link JobSummaryDTO}.
     */
    private Comparator<JobSummaryDTO> buildVirtualComparator(String sortBy, SortDirection direction, boolean distanceSorting) {
        SortDirection dir = direction != null ? direction : SortDirection.DESC;
        Comparator<JobSummaryDTO> comparator;

        if (distanceSorting || "distance".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparing(
                    JobSummaryDTO::getDistanceKm,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
        } else if ("bidcount".equalsIgnoreCase(sortBy) || "viewcount".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparing(
                    JobSummaryDTO::getPopularityScore,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
        } else {
            // fallback: createdAt DESC
            comparator = Comparator.comparing(
                    JobSummaryDTO::getCreatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            );
        }

        return dir == SortDirection.DESC ? comparator.reversed() : comparator;
    }

    // ── Safe Redis geo wrappers ──────────────────────────────────
    /**
     * Adds a job to the Redis geo-index. If the Redis write fails, records the
     * failure in PostgreSQL for later retry, ensuring the PostgreSQL
     * transaction is NOT rolled back.
     */
    private void safeGeoAdd(Long jobId, Double latitude, Double longitude) {
        try {
            jobGeoService.addOrUpdateOpenJob(jobId, latitude, longitude);
        } catch (Exception ex) {
            log.error("Redis geo ADD failed for jobId={}, scheduling retry: {}", jobId, ex.getMessage());
            recordFailedSync(jobId, FailedGeoSync.SyncOperation.ADD, latitude, longitude, ex.getMessage());
        }
    }

    /**
     * Removes a job from the Redis geo-index. If the Redis write fails, records
     * the failure for later retry.
     */
    private void safeGeoRemove(Long jobId) {
        try {
            jobGeoService.removeOpenJob(jobId);
        } catch (Exception ex) {
            log.error("Redis geo REMOVE failed for jobId={}, scheduling retry: {}", jobId, ex.getMessage());
            recordFailedSync(jobId, FailedGeoSync.SyncOperation.REMOVE, null, null, ex.getMessage());
        }
    }

    private void recordFailedSync(Long jobId, FailedGeoSync.SyncOperation operation,
            Double latitude, Double longitude, String error) {
        try {
            if (!failedGeoSyncRepository.existsByJobIdAndOperationAndResolvedFalse(jobId, operation)) {
                failedGeoSyncRepository.save(FailedGeoSync.builder()
                        .jobId(jobId)
                        .operation(operation)
                        .latitude(latitude)
                        .longitude(longitude)
                        .lastError(error != null && error.length() > 1000 ? error.substring(0, 1000) : error)
                        .build());
            }
        } catch (Exception ex) {
            log.error("Failed to record geo sync failure for jobId={}: {}", jobId, ex.getMessage());
        }
    }
}
