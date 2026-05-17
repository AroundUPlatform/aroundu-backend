package com.beingadish.AroundU.unit.service;

import com.aroundu.ranking.v1.*;
import com.beingadish.AroundU.infrastructure.ranking.RankingEngineClient;
import com.beingadish.AroundU.job.dto.JobSummaryDTO;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.GrpcCleanupRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RankingEngineClient")
class RankingEngineClientTest {

    // ── Disabled client tests ─────────────────────────────────────

    @Nested
    @DisplayName("when disabled")
    class WhenDisabled {

        private RankingEngineClient client;

        @BeforeEach
        void setUp() {
            client = new RankingEngineClient(null, false);
        }

        @Test
        @DisplayName("isAvailable returns false")
        void isAvailable_ReturnsFalse() {
            assertFalse(client.isAvailable());
        }

        @Test
        @DisplayName("getWorkerFeed returns unavailable result")
        void getWorkerFeed_ReturnsUnavailable() {
            RankingEngineClient.RankedFeedResult result = client.getWorkerFeed(
                    1L, 40.7128, -74.0060, 25.0, 0, 20, Collections.emptyList());
            assertFalse(result.isAvailable());
            assertTrue(result.getJobs().isEmpty());
        }

        @Test
        @DisplayName("getWorkerRecommendations returns unavailable result")
        void getWorkerRecommendations_ReturnsUnavailable() {
            RankingEngineClient.RankedWorkerResult result = client.getWorkerRecommendations(1L, 1L, 10);
            assertFalse(result.isAvailable());
            assertTrue(result.getWorkers().isEmpty());
        }

        @Test
        @DisplayName("recordInteraction does nothing when disabled")
        void recordInteraction_NoOp() {
            // Should not throw
            assertDoesNotThrow(() -> client.recordInteraction(1L, 1L, InteractionType.VIEWED));
        }
    }

    // ── Enabled client with in-process gRPC server ────────────────

    @Nested
    @DisplayName("when enabled with mock server")
    class WhenEnabled {

        private final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
        private RankingEngineClient client;

        @BeforeEach
        void setUp() throws Exception {
            String serverName = InProcessServerBuilder.generateName();

            grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new FakeRankingService())
                    .build()
                    .start());

            ManagedChannel channel = grpcCleanup.register(
                    InProcessChannelBuilder.forName(serverName).directExecutor().build());

            client = new RankingEngineClient(channel, true);
        }

        @Test
        @DisplayName("isAvailable returns true")
        void isAvailable_ReturnsTrue() {
            assertTrue(client.isAvailable());
        }

        @Test
        @DisplayName("getWorkerFeed returns ranked jobs")
        void getWorkerFeed_ReturnsJobs() {
            RankingEngineClient.RankedFeedResult result = client.getWorkerFeed(
                    1L, 40.7128, -74.0060, 25.0, 0, 20, Collections.emptyList());

            assertTrue(result.isAvailable());
            assertEquals(2, result.getJobs().size());
            assertEquals(5, result.getTotalCandidates());
            assertEquals(3, result.getTotalFiltered());

            JobSummaryDTO first = result.getJobs().get(0);
            assertEquals(100L, first.getId());
            assertEquals("Plumbing Fix", first.getTitle());
            assertEquals(2.5, first.getDistanceKm());
        }

        @Test
        @DisplayName("getWorkerRecommendations returns ranked workers")
        void getWorkerRecommendations_ReturnsWorkers() {
            RankingEngineClient.RankedWorkerResult result = client.getWorkerRecommendations(1L, 1L, 10);

            assertTrue(result.isAvailable());
            assertEquals(1, result.getWorkers().size());
            assertEquals("42", result.getWorkers().get(0).getWorkerId());
            assertEquals(0.95, result.getWorkers().get(0).getMatchScore(), 0.01);
        }

        @Test
        @DisplayName("recordInteraction succeeds")
        void recordInteraction_Succeeds() {
            assertDoesNotThrow(() -> client.recordInteraction(1L, 1L, InteractionType.VIEWED));
        }
    }

    // ── Error handling tests ──────────────────────────────────────

    @Nested
    @DisplayName("when server returns errors")
    class WhenServerErrors {

        private final GrpcCleanupRule grpcCleanup = new GrpcCleanupRule();
        private RankingEngineClient client;

        @BeforeEach
        void setUp() throws Exception {
            String serverName = InProcessServerBuilder.generateName();

            grpcCleanup.register(InProcessServerBuilder.forName(serverName)
                    .directExecutor()
                    .addService(new ErrorRankingService())
                    .build()
                    .start());

            ManagedChannel channel = grpcCleanup.register(
                    InProcessChannelBuilder.forName(serverName).directExecutor().build());

            client = new RankingEngineClient(channel, true);
        }

        @Test
        @DisplayName("getWorkerFeed returns unavailable on gRPC error")
        void getWorkerFeed_HandlesGrpcError() {
            RankingEngineClient.RankedFeedResult result = client.getWorkerFeed(
                    1L, 40.7128, -74.0060, 25.0, 0, 20, Collections.emptyList());
            assertFalse(result.isAvailable());
            assertTrue(result.getJobs().isEmpty());
        }

        @Test
        @DisplayName("getWorkerRecommendations returns unavailable on gRPC error")
        void getWorkerRecommendations_HandlesGrpcError() {
            RankingEngineClient.RankedWorkerResult result = client.getWorkerRecommendations(1L, 1L, 10);
            assertFalse(result.isAvailable());
            assertTrue(result.getWorkers().isEmpty());
        }

        @Test
        @DisplayName("recordInteraction does not throw on gRPC error")
        void recordInteraction_HandlesGrpcError() {
            assertDoesNotThrow(() -> client.recordInteraction(1L, 1L, InteractionType.BID_PLACED));
        }
    }

    // ── RankedFeedResult tests ────────────────────────────────────

    @Nested
    @DisplayName("RankedFeedResult")
    class RankedFeedResultTest {

        @Test
        @DisplayName("unavailable result has correct defaults")
        void unavailable() {
            RankingEngineClient.RankedFeedResult result = RankingEngineClient.RankedFeedResult.unavailable();
            assertFalse(result.isAvailable());
            assertTrue(result.getJobs().isEmpty());
            assertEquals(0, result.getTotalCandidates());
            assertEquals(0, result.getTotalFiltered());
        }

        @Test
        @DisplayName("success result carries data")
        void success() {
            JobSummaryDTO dto = new JobSummaryDTO();
            dto.setId(1L);
            RankingEngineClient.RankedFeedResult result =
                    RankingEngineClient.RankedFeedResult.success(List.of(dto), 10, 5);
            assertTrue(result.isAvailable());
            assertEquals(1, result.getJobs().size());
            assertEquals(10, result.getTotalCandidates());
            assertEquals(5, result.getTotalFiltered());
        }
    }

    // ── Fake gRPC service implementations ─────────────────────────

    private static class FakeRankingService extends RankingServiceGrpc.RankingServiceImplBase {

        @Override
        public void getWorkerFeed(WorkerFeedRequest request, StreamObserver<WorkerFeedResponse> responseObserver) {
            WorkerFeedResponse response = WorkerFeedResponse.newBuilder()
                    .setTotalCandidates(5)
                    .setTotalFiltered(3)
                    .addJobs(RankedJob.newBuilder()
                            .setJobId("100")
                            .setTitle("Plumbing Fix")
                            .setRankScore(0.87)
                            .setDistanceKm(2.5)
                            .setPriceAmount(150.0)
                            .setJobUrgency("NORMAL")
                            .build())
                    .addJobs(RankedJob.newBuilder()
                            .setJobId("200")
                            .setTitle("Electrical Repair")
                            .setRankScore(0.72)
                            .setDistanceKm(5.1)
                            .setPriceAmount(200.0)
                            .setJobUrgency("URGENT")
                            .build())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void getWorkerRecommendations(WorkerRecommendationRequest request,
                                              StreamObserver<WorkerRecommendationResponse> responseObserver) {
            WorkerRecommendationResponse response = WorkerRecommendationResponse.newBuilder()
                    .addWorkers(RankedWorker.newBuilder()
                            .setWorkerId("42")
                            .setMatchScore(0.95)
                            .setDistanceKm(1.2)
                            .setAverageRating(4.8)
                            .setIsVerified(true)
                            .build())
                    .build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void recordInteraction(RecordInteractionRequest request,
                                       StreamObserver<RecordInteractionResponse> responseObserver) {
            responseObserver.onNext(RecordInteractionResponse.newBuilder().setSuccess(true).build());
            responseObserver.onCompleted();
        }
    }

    private static class ErrorRankingService extends RankingServiceGrpc.RankingServiceImplBase {

        @Override
        public void getWorkerFeed(WorkerFeedRequest request, StreamObserver<WorkerFeedResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.UNAVAILABLE.withDescription("Service down")));
        }

        @Override
        public void getWorkerRecommendations(WorkerRecommendationRequest request,
                                              StreamObserver<WorkerRecommendationResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.INTERNAL.withDescription("Internal error")));
        }

        @Override
        public void recordInteraction(RecordInteractionRequest request,
                                       StreamObserver<RecordInteractionResponse> responseObserver) {
            responseObserver.onError(new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("Timeout")));
        }
    }
}
