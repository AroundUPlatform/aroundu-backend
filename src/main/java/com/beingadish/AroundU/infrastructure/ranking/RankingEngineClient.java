package com.beingadish.AroundU.infrastructure.ranking;

import com.aroundu.ranking.v1.*;
import com.beingadish.AroundU.common.constants.enums.JobUrgency;
import com.beingadish.AroundU.common.dto.PriceDTO;
import com.beingadish.AroundU.job.dto.JobSummaryDTO;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * gRPC client for the AroundU Ranking Engine (Rust service).
 * Provides ranked job feeds for workers and worker recommendations for clients.
 * Falls back gracefully when the ranking engine is unavailable.
 */
@Service
@Slf4j
public class RankingEngineClient {

    private final RankingServiceGrpc.RankingServiceBlockingStub stub;
    private final boolean enabled;

    public RankingEngineClient(
            ObjectProvider<ManagedChannel> rankingEngineChannelProvider,
            @Qualifier("rankingEngineEnabled") boolean rankingEngineEnabled) {
        this.enabled = rankingEngineEnabled;
        ManagedChannel rankingEngineChannel = rankingEngineChannelProvider.getIfAvailable();
        if (rankingEngineChannel != null) {
            this.stub = RankingServiceGrpc.newBlockingStub(rankingEngineChannel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS);
        } else {
            this.stub = null;
        }
    }

    /**
     * Returns true if the ranking engine integration is enabled and the stub is available.
     */
    public boolean isAvailable() {
        return enabled && stub != null;
    }

    /**
     * Fetches a ranked job feed from the Rust ranking engine.
     *
     * @return ranked jobs as JobSummaryDTOs, or empty list if the engine is unavailable
     */
    public RankedFeedResult getWorkerFeed(Long workerId, Double latitude, Double longitude,
                                          Double radiusKm, int page, int pageSize,
                                          List<String> excludeJobIds) {
        if (!isAvailable()) {
            return RankedFeedResult.unavailable();
        }

        try {
            WorkerFeedRequest.Builder builder = WorkerFeedRequest.newBuilder()
                    .setWorkerId(workerId.toString())
                    .setPage(page)
                    .setPageSize(pageSize);

            if (latitude != null) builder.setLatitude(latitude);
            if (longitude != null) builder.setLongitude(longitude);
            if (radiusKm != null) builder.setRadiusKm(radiusKm);
            if (excludeJobIds != null) builder.addAllExcludeJobIds(excludeJobIds);

            WorkerFeedResponse response = stub.getWorkerFeed(builder.build());

            List<JobSummaryDTO> jobs = response.getJobsList().stream()
                    .map(this::toJobSummaryDTO)
                    .collect(Collectors.toList());

            log.debug("Ranking engine returned {} jobs for worker {} (candidates={}, filtered={})",
                    jobs.size(), workerId, response.getTotalCandidates(), response.getTotalFiltered());

            return RankedFeedResult.success(jobs, response.getTotalCandidates(), response.getTotalFiltered());
        } catch (StatusRuntimeException e) {
            log.warn("Ranking engine gRPC call failed for worker {}: {} - {}",
                    workerId, e.getStatus().getCode(), e.getStatus().getDescription());
            return RankedFeedResult.unavailable();
        } catch (Exception e) {
            log.error("Unexpected error calling ranking engine for worker {}", workerId, e);
            return RankedFeedResult.unavailable();
        }
    }

    /**
     * Fetches ranked worker recommendations from the Rust ranking engine.
     */
    public RankedWorkerResult getWorkerRecommendations(Long jobId, Long clientId, int limit) {
        if (!isAvailable()) {
            return RankedWorkerResult.unavailable();
        }

        try {
            WorkerRecommendationRequest request = WorkerRecommendationRequest.newBuilder()
                    .setJobId(jobId.toString())
                    .setClientId(clientId.toString())
                    .setLimit(limit)
                    .build();

            WorkerRecommendationResponse response = stub.getWorkerRecommendations(request);

            log.debug("Ranking engine returned {} worker recommendations for job {}",
                    response.getWorkersCount(), jobId);

            return RankedWorkerResult.success(response.getWorkersList());
        } catch (StatusRuntimeException e) {
            log.warn("Ranking engine gRPC call failed for job {}: {} - {}",
                    jobId, e.getStatus().getCode(), e.getStatus().getDescription());
            return RankedWorkerResult.unavailable();
        } catch (Exception e) {
            log.error("Unexpected error calling ranking engine for job {}", jobId, e);
            return RankedWorkerResult.unavailable();
        }
    }

    /**
     * Records a user interaction event in the ranking engine.
     */
    public void recordInteraction(Long workerId, Long jobId, InteractionType eventType) {
        if (!isAvailable()) {
            return;
        }

        try {
            RecordInteractionRequest request = RecordInteractionRequest.newBuilder()
                    .setWorkerId(workerId.toString())
                    .setJobId(jobId.toString())
                    .setEventType(eventType)
                    .build();

            stub.recordInteraction(request);
            log.debug("Recorded interaction: worker={}, job={}, type={}", workerId, jobId, eventType);
        } catch (Exception e) {
            // Fire-and-forget: don't fail the main request
            log.warn("Failed to record interaction in ranking engine: {}", e.getMessage());
        }
    }

    private JobSummaryDTO toJobSummaryDTO(RankedJob rankedJob) {
        JobSummaryDTO dto = new JobSummaryDTO();

        try {
            dto.setId(Long.parseLong(rankedJob.getJobId()));
        } catch (NumberFormatException e) {
            log.warn("Invalid job ID from ranking engine: {}", rankedJob.getJobId());
        }

        dto.setTitle(rankedJob.getTitle());
        dto.setDistanceKm(rankedJob.getDistanceKm());

        if (rankedJob.getPriceAmount() > 0) {
            PriceDTO price = new PriceDTO();
            price.setAmount(rankedJob.getPriceAmount());
            dto.setPrice(price);
        }

        if (!rankedJob.getJobUrgency().isEmpty()) {
            try {
                dto.setJobUrgency(JobUrgency.valueOf(rankedJob.getJobUrgency()));
            } catch (IllegalArgumentException e) {
                // ignore unknown urgency
            }
        }

        return dto;
    }

    /**
     * Result wrapper for ranked job feed responses.
     */
    public static class RankedFeedResult {
        private final boolean available;
        private final List<JobSummaryDTO> jobs;
        private final int totalCandidates;
        private final int totalFiltered;

        private RankedFeedResult(boolean available, List<JobSummaryDTO> jobs, int totalCandidates, int totalFiltered) {
            this.available = available;
            this.jobs = jobs;
            this.totalCandidates = totalCandidates;
            this.totalFiltered = totalFiltered;
        }

        public static RankedFeedResult success(List<JobSummaryDTO> jobs, int totalCandidates, int totalFiltered) {
            return new RankedFeedResult(true, jobs, totalCandidates, totalFiltered);
        }

        public static RankedFeedResult unavailable() {
            return new RankedFeedResult(false, Collections.emptyList(), 0, 0);
        }

        public boolean isAvailable() {
            return available;
        }

        public List<JobSummaryDTO> getJobs() {
            return jobs;
        }

        public int getTotalCandidates() {
            return totalCandidates;
        }

        public int getTotalFiltered() {
            return totalFiltered;
        }
    }

    /**
     * Result wrapper for ranked worker recommendation responses.
     */
    public static class RankedWorkerResult {
        private final boolean available;
        private final List<RankedWorker> workers;

        private RankedWorkerResult(boolean available, List<RankedWorker> workers) {
            this.available = available;
            this.workers = workers;
        }

        public static RankedWorkerResult success(List<RankedWorker> workers) {
            return new RankedWorkerResult(true, workers);
        }

        public static RankedWorkerResult unavailable() {
            return new RankedWorkerResult(false, Collections.emptyList());
        }

        public boolean isAvailable() {
            return available;
        }

        public List<RankedWorker> getWorkers() {
            return workers;
        }
    }
}
