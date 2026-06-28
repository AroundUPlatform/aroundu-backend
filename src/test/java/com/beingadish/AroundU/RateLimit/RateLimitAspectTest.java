package com.beingadish.AroundU.RateLimit;

import com.beingadish.AroundU.common.exception.RateLimitExceededException;
import com.beingadish.AroundU.infrastructure.ratelimit.RateLimit;
import com.beingadish.AroundU.infrastructure.ratelimit.RateLimitAspect;
import com.beingadish.AroundU.infrastructure.security.UserPrincipal;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for the rate-limiting aspect.
 * <p>
 * Covers: token consumption, rejection with correct headers, admin bypass,
 * disabled mode, concurrent requests, bucket key format, and anonymous/IP-based
 * limiting.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Rate Limiting")
class RateLimitAspectTest {

    @Mock
    private ProxyManager<String> proxyManager;
    @Mock
    private RemoteBucketBuilder<String> bucketBuilder;
    @Mock
    private BucketProxy bucketProxy;
    @Mock
    private ProceedingJoinPoint joinPoint;
    @Mock
    private MethodSignature methodSignature;

    private RateLimitAspect aspect;

    // Default annotation values for most tests
    private RateLimit defaultRateLimit;

    @BeforeEach
    void setUp() throws Exception {
        aspect = new RateLimitAspect(proxyManager, true);
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();

        // Create a default @RateLimit proxy (capacity=5, refill=5, period=60min)
        defaultRateLimit = createRateLimit(5, 5, 60);

        // Wire up join point → method signature
        lenient().when(joinPoint.getSignature()).thenReturn(methodSignature);
        lenient().when(methodSignature.getDeclaringType()).thenReturn(FakeController.class);
        lenient().when(methodSignature.getName()).thenReturn("createJob");
        try {
            lenient().when(joinPoint.proceed()).thenReturn("OK");
        } catch (Throwable ignored) {
        }
    }

    // ─── Helper: build a mock @RateLimit annotation ────────────────────
    @SuppressWarnings("all")
    private RateLimit createRateLimit(int capacity, int refillTokens, int refillMinutes) {
        return new RateLimit() {
            @Override
            public int capacity() {
                return capacity;
            }

            @Override
            public int refillTokens() {
                return refillTokens;
            }

            @Override
            public int refillMinutes() {
                return refillMinutes;
            }

            @Override
            public Class<RateLimit> annotationType() {
                return RateLimit.class;
            }
        };
    }

    private void authenticateUser(Long userId, String role) {
        UserPrincipal principal = UserPrincipal.builder()
                .id(userId)
                .email("user@test.com")
                .password("password")
                .authorities(List.of(new SimpleGrantedAuthority(role)))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void setUpMockBucket(boolean consumed, long remainingTokens, long nanosToWait) {
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        lenient().when(probe.isConsumed()).thenReturn(consumed);
        lenient().when(probe.getRemainingTokens()).thenReturn(remainingTokens);
        lenient().when(probe.getNanosToWaitForRefill()).thenReturn(nanosToWait);

        lenient().when(proxyManager.builder()).thenReturn(bucketBuilder);
        lenient().when(bucketBuilder.build(anyString(), any(Supplier.class))).thenReturn(bucketProxy);
        lenient().when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenReturn(probe);
    }

    // Placeholder for method-signature resolution
    static class FakeController {

        public String createJob() {
            return "created";
        }
    }

    // =====================================================================
    //  Token consumption tests
    // =====================================================================
    @Nested
    @DisplayName("Token Consumption")
    class TokenConsumptionTests {

        @Test
        @DisplayName("should allow request when tokens remain")
        void allowsRequest_whenTokensRemain() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            setUpMockBucket(true, 4, 0);

            Object result = aspect.enforce(joinPoint, defaultRateLimit);

            assertThat(result).isEqualTo("OK");
            verify(joinPoint).proceed();
        }

        @Test
        @DisplayName("5th request succeeds, 6th fails (capacity=5)")
        void fifthSucceeds_sixthFails() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");

            // First 5 succeed
            setUpMockBucket(true, 0, 0);
            for (int i = 0; i < 5; i++) {
                Object result = aspect.enforce(joinPoint, defaultRateLimit);
                assertThat(result).isEqualTo("OK");
            }

            // 6th fails
            long retryNanos = Duration.ofSeconds(45).toNanos();
            setUpMockBucket(false, 0, retryNanos);

            assertThatThrownBy(() -> aspect.enforce(joinPoint, defaultRateLimit))
                    .isInstanceOf(RateLimitExceededException.class);
        }

        @Test
        @DisplayName("should throw RateLimitExceededException with correct metadata")
        void throwsException_withCorrectMetadata() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            long retryNanos = Duration.ofSeconds(45).toNanos();
            setUpMockBucket(false, 0, retryNanos);

            assertThatThrownBy(() -> aspect.enforce(joinPoint, defaultRateLimit))
                    .isInstanceOf(RateLimitExceededException.class)
                    .satisfies(ex -> {
                        RateLimitExceededException rle = (RateLimitExceededException) ex;
                        assertThat(rle.getLimit()).isEqualTo(5);
                        assertThat(rle.getRetryAfterSeconds()).isEqualTo(45);
                        assertThat(rle.getMessage()).contains("45 seconds");
                    });
        }

        @Test
        @DisplayName("should not proceed with method when rate limited")
        void doesNotProceed_whenRateLimited() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            setUpMockBucket(false, 0, Duration.ofSeconds(30).toNanos());

            assertThatThrownBy(() -> aspect.enforce(joinPoint, defaultRateLimit))
                    .isInstanceOf(RateLimitExceededException.class);

            verify(joinPoint, never()).proceed();
        }
    }

    // =====================================================================
    //  Admin bypass tests
    // =====================================================================
    @Nested
    @DisplayName("Admin Bypass")
    class AdminBypassTests {

        @Test
        @DisplayName("should bypass rate limiting for ROLE_ADMIN")
        void bypassForAdmin() throws Throwable {
            authenticateUser(1L, "ROLE_ADMIN");

            Object result = aspect.enforce(joinPoint, defaultRateLimit);

            assertThat(result).isEqualTo("OK");
            verify(joinPoint).proceed();
            verifyNoInteractions(proxyManager);  // No bucket checked
        }

        @Test
        @DisplayName("should enforce rate limiting for ROLE_CLIENT")
        void enforceForClient() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            setUpMockBucket(true, 4, 0);

            aspect.enforce(joinPoint, defaultRateLimit);

            verify(proxyManager).builder();  // Bucket checked
        }

        @Test
        @DisplayName("should enforce rate limiting for ROLE_WORKER")
        void enforceForWorker() throws Throwable {
            authenticateUser(99L, "ROLE_WORKER");
            setUpMockBucket(true, 4, 0);

            aspect.enforce(joinPoint, defaultRateLimit);

            verify(proxyManager).builder();
        }
    }

    // =====================================================================
    //  Disabled mode tests
    // =====================================================================
    @Nested
    @DisplayName("Disabled Mode")
    class DisabledModeTests {

        @Test
        @DisplayName("should skip rate limiting when disabled")
        void skipWhenDisabled() throws Throwable {
            RateLimitAspect disabledAspect = new RateLimitAspect(proxyManager, false);
            authenticateUser(42L, "ROLE_CLIENT");

            Object result = disabledAspect.enforce(joinPoint, defaultRateLimit);

            assertThat(result).isEqualTo("OK");
            verify(joinPoint).proceed();
            verifyNoInteractions(proxyManager);
        }
    }

    // =====================================================================
    //  Bucket key format tests
    // =====================================================================
    @Nested
    @DisplayName("Bucket Key Format")
    class BucketKeyTests {

        @Test
        @DisplayName("should use userId in bucket key for authenticated users")
        void usesUserId_forAuthenticated() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            setUpMockBucket(true, 4, 0);

            aspect.enforce(joinPoint, defaultRateLimit);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
            assertThat(keyCaptor.getValue()).isEqualTo("rate-limit:FakeController.createJob:user:42");
        }

        @Test
        @DisplayName("should use IP in bucket key for anonymous requests")
        void usesIp_forAnonymous() throws Throwable {
            // No authentication set
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("192.168.1.100");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            setUpMockBucket(true, 4, 0);

            aspect.enforce(joinPoint, defaultRateLimit);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
            assertThat(keyCaptor.getValue()).isEqualTo("rate-limit:FakeController.createJob:ip:192.168.1.100");
        }

        @Test
        @DisplayName("should use X-Forwarded-For header when present")
        void usesForwardedIp() throws Throwable {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            setUpMockBucket(true, 4, 0);

            aspect.enforce(joinPoint, defaultRateLimit);

            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            verify(bucketBuilder).build(keyCaptor.capture(), any(Supplier.class));
            assertThat(keyCaptor.getValue()).contains("ip:203.0.113.50");
        }
    }

    // =====================================================================
    //  Different limit configuration tests
    // =====================================================================
    @Nested
    @DisplayName("Limit Configurations")
    class LimitConfigTests {

        @Test
        @DisplayName("should use annotation values for bucket configuration")
        void usesAnnotationValues() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            RateLimit customLimit = createRateLimit(20, 20, 60);

            // Capture the config supplier
            setUpMockBucket(true, 19, 0);

            aspect.enforce(joinPoint, customLimit);

            ArgumentCaptor<Supplier<BucketConfiguration>> configCaptor
                    = ArgumentCaptor.forClass(Supplier.class);
            verify(bucketBuilder).build(anyString(), configCaptor.capture());

            BucketConfiguration config = configCaptor.getValue().get();
            assertThat(config).isNotNull();
        }

        @Test
        @DisplayName("should handle different refill periods")
        void handlesDifferentRefillPeriods() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");

            // 30 requests per minute (worker feed)
            RateLimit feedLimit = createRateLimit(30, 30, 1);
            setUpMockBucket(true, 29, 0);

            Object result = aspect.enforce(joinPoint, feedLimit);
            assertThat(result).isEqualTo("OK");
        }
    }

    // =====================================================================
    //  Exception response tests
    // =====================================================================
    @Nested
    @DisplayName("Exception Response")
    class ExceptionResponseTests {

        @Test
        @DisplayName("exception should carry limit and retry-after")
        void exceptionCarriesMetadata() {
            RateLimitExceededException ex = new RateLimitExceededException(5, 45);

            assertThat(ex.getLimit()).isEqualTo(5);
            assertThat(ex.getRetryAfterSeconds()).isEqualTo(45);
            assertThat(ex.getMessage()).isEqualTo("Too many requests. Try again in 45 seconds.");
        }

        @Test
        @DisplayName("retry-after should be at least 1 second")
        void retryAfter_atLeastOneSecond() throws Throwable {
            authenticateUser(42L, "ROLE_CLIENT");
            // Very small nanos (less than 1 second)
            setUpMockBucket(false, 0, 500_000_000L); // 0.5 seconds

            assertThatThrownBy(() -> aspect.enforce(joinPoint, defaultRateLimit))
                    .isInstanceOf(RateLimitExceededException.class)
                    .satisfies(ex -> {
                        RateLimitExceededException rle = (RateLimitExceededException) ex;
                        assertThat(rle.getRetryAfterSeconds()).isGreaterThanOrEqualTo(1);
                    });
        }
    }

    // =====================================================================
    //  Concurrent request tests (thread safety)
    // =====================================================================
    @Nested
    @DisplayName("Concurrent Requests")
    class ConcurrentRequestTests {

        @Test
        @DisplayName("should handle concurrent requests safely")
        void handlesConcurrentRequests() throws Exception {
            // Set up a bucket that allows first 5, rejects rest
            AtomicInteger allowedCount = new AtomicInteger(5);

            ConsumptionProbe allowedProbe = mock(ConsumptionProbe.class);
            when(allowedProbe.isConsumed()).thenReturn(true);
            when(allowedProbe.getRemainingTokens()).thenReturn(4L);

            ConsumptionProbe rejectedProbe = mock(ConsumptionProbe.class);
            when(rejectedProbe.isConsumed()).thenReturn(false);
            when(rejectedProbe.getNanosToWaitForRefill()).thenReturn(Duration.ofSeconds(30).toNanos());

            when(proxyManager.builder()).thenReturn(bucketBuilder);
            when(bucketBuilder.build(anyString(), any(Supplier.class))).thenReturn(bucketProxy);
            when(bucketProxy.tryConsumeAndReturnRemaining(1)).thenAnswer(invocation -> {
                if (allowedCount.decrementAndGet() >= 0) {
                    return allowedProbe;
                }
                return rejectedProbe;
            });

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger succeeded = new AtomicInteger(0);
            AtomicInteger rateLimited = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        // Each thread gets its own security context
                        authenticateUser(42L, "ROLE_CLIENT");
                        ProceedingJoinPoint localJp = mock(ProceedingJoinPoint.class);
                        when(localJp.getSignature()).thenReturn(methodSignature);
                        try {
                            when(localJp.proceed()).thenReturn("OK");
                        } catch (Throwable ignored) {
                        }

                        aspect.enforce(localJp, defaultRateLimit);
                        succeeded.incrementAndGet();
                    } catch (RateLimitExceededException e) {
                        rateLimited.incrementAndGet();
                    } catch (Throwable e) {
                        // Unexpected
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertThat(succeeded.get() + rateLimited.get()).isEqualTo(threadCount);
            assertThat(succeeded.get()).isEqualTo(5);
            assertThat(rateLimited.get()).isEqualTo(5);
        }
    }
}
