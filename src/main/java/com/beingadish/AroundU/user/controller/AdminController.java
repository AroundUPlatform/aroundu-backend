package com.beingadish.AroundU.user.controller;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.beingadish.AroundU.common.constants.URIConstants.ADMIN_BASE;
import com.beingadish.AroundU.common.constants.enums.JobStatus;
import com.beingadish.AroundU.common.dto.ApiResponse;
import com.beingadish.AroundU.common.util.PageResponse;
import com.beingadish.AroundU.job.dto.JobSummaryDTO;
import com.beingadish.AroundU.job.mapper.JobMapper;
import com.beingadish.AroundU.job.repository.JobRepository;
import com.beingadish.AroundU.user.repository.ClientRepository;
import com.beingadish.AroundU.user.repository.WorkerRepository;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Admin-only endpoints for platform overview and management. All endpoints
 * require ROLE_ADMIN.
 */
@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin", description = "Admin dashboard and management endpoints")
public class AdminController {

    private final JobRepository jobRepository;
    private final ClientRepository clientRepository;
    private final WorkerRepository workerRepository;
    private final JobMapper jobMapper;

    @Value("${prometheus.url:http://prometheus:9090}")
    private String prometheusUrl;

    @GetMapping("/overview")
    @Operation(summary = "Platform overview",
            description = "Returns key platform statistics: total users, active jobs, today's activity.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = LocalDate.now().atTime(LocalTime.MAX);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalClients", clientRepository.count());
        stats.put("totalWorkers", workerRepository.count());
        stats.put("activeJobs", jobRepository.countByJobStatus(JobStatus.IN_PROGRESS));
        stats.put("openJobs", jobRepository.countByJobStatus(JobStatus.OPEN_FOR_BIDS));
        stats.put("jobsCreatedToday", jobRepository.countByCreatedAtBetween(todayStart, todayEnd));
        stats.put("jobsCompletedToday", jobRepository.countByJobStatusAndCreatedAtBetween(
                JobStatus.COMPLETED, todayStart, todayEnd));

        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/jobs")
    @Operation(summary = "List all jobs (admin)",
            description = "Returns a paginated list of all jobs, optionally filtered by statuses.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ApiResponse<PageResponse<JobSummaryDTO>>> listJobs(
            @Parameter(description = "Comma-separated job statuses to filter")
            @RequestParam(required = false) List<String> statuses,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        var pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<JobSummaryDTO> dtoPage;
        if (statuses != null && !statuses.isEmpty()) {
            var statusEnums = statuses.stream()
                    .map(s -> JobStatus.valueOf(s.toUpperCase()))
                    .collect(Collectors.toList());
            dtoPage = jobRepository.findByJobStatusIn(statusEnums, pageable).map(jobMapper::toSummaryDto);
        } else {
            dtoPage = jobRepository.findAll(pageable).map(jobMapper::toSummaryDto);
        }
        return ResponseEntity.ok(ApiResponse.success(new PageResponse<>(dtoPage)));
    }

    @GetMapping("/metrics/query")
    @Operation(summary = "Proxy Prometheus query",
            description = "Proxies a PromQL query to Prometheus and returns the JSON result.",
            security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<String> metricsQuery(
            @RequestParam String query,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end,
            @RequestParam(required = false) String step) {
        try {
            boolean isRange = start != null && end != null;
            String endpoint = isRange ? "/api/v1/query_range" : "/api/v1/query";
            StringBuilder sb = new StringBuilder(prometheusUrl + endpoint);
            sb.append("?query=").append(URLEncoder.encode(query, StandardCharsets.UTF_8));
            if (isRange) {
                sb.append("&start=").append(URLEncoder.encode(start, StandardCharsets.UTF_8));
                sb.append("&end=").append(URLEncoder.encode(end, StandardCharsets.UTF_8));
                if (step != null) {
                    sb.append("&step=").append(URLEncoder.encode(step, StandardCharsets.UTF_8));
                }
            }

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(sb.toString()))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(resp.statusCode())
                    .header("Content-Type", "application/json")
                    .body(resp.body());
        } catch (Exception e) {
            return ResponseEntity.status(503)
                    .header("Content-Type", "application/json")
                    .body("{\"status\":\"error\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
