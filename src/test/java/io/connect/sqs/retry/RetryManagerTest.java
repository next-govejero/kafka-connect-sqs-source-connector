package io.connect.sqs.retry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetryManagerTest {

    private RetryManager retryManager;

    @BeforeEach
    void setUp() {
        // Default: 3 retries, 1000ms base backoff
        retryManager = new RetryManager(3, 1000L);
    }

    @Test
    void shouldRetryOnFirstFailure() {
        Exception error = new RuntimeException("Test error");
        RetryDecision decision = retryManager.recordFailure("msg-1", error);

        assertThat(decision.shouldRetry()).isTrue();
        assertThat(decision.getAttemptNumber()).isEqualTo(1);
        assertThat(decision.getBackoffMs()).isGreaterThan(0);
    }

    @Test
    void shouldRetryOnSecondFailure() {
        Exception error = new RuntimeException("Test error");

        // First failure
        retryManager.recordFailure("msg-1", error);

        // Second failure
        RetryDecision decision = retryManager.recordFailure("msg-1", error);

        assertThat(decision.shouldRetry()).isTrue();
        assertThat(decision.getAttemptNumber()).isEqualTo(2);
    }

    @Test
    void shouldRouteToDlqAfterMaxRetries() {
        Exception error = new RuntimeException("Test error");

        // Exhaust all retries
        retryManager.recordFailure("msg-1", error); // 1st attempt
        retryManager.recordFailure("msg-1", error); // 2nd attempt
        RetryDecision decision = retryManager.recordFailure("msg-1", error); // 3rd attempt

        assertThat(decision.shouldRetry()).isFalse();
        assertThat(decision.getAttemptNumber()).isEqualTo(3);
    }

    @Test
    void shouldTrackRetryCountCorrectly() {
        Exception error = new RuntimeException("Test error");

        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(0);

        retryManager.recordFailure("msg-1", error);
        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(1);

        retryManager.recordFailure("msg-1", error);
        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(2);

        retryManager.recordFailure("msg-1", error);
        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(3);
    }

    @Test
    void shouldClearRetryInfoSuccessfully() {
        Exception error = new RuntimeException("Test error");
        retryManager.recordFailure("msg-1", error);

        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(1);

        retryManager.clearRetryInfo("msg-1");

        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(0);
    }

    @Test
    void shouldTrackLastError() {
        RuntimeException error1 = new RuntimeException("First error");
        RuntimeException error2 = new RuntimeException("Second error");

        retryManager.recordFailure("msg-1", error1);
        assertThat(retryManager.getLastError("msg-1")).isEqualTo(error1);

        retryManager.recordFailure("msg-1", error2);
        assertThat(retryManager.getLastError("msg-1")).isEqualTo(error2);
    }

    @Test
    void shouldCalculateExponentialBackoff() {
        // Create manager with no jitter for deterministic testing
        RetryManager noJitterManager = new RetryManager(5, 1000L, 0.0, 2.0);

        // First attempt: base = 1000ms
        long backoff1 = noJitterManager.calculateBackoffWithJitter(1);
        assertThat(backoff1).isEqualTo(1000L);

        // Second attempt: 1000 * 2^1 = 2000ms
        long backoff2 = noJitterManager.calculateBackoffWithJitter(2);
        assertThat(backoff2).isEqualTo(2000L);

        // Third attempt: 1000 * 2^2 = 4000ms
        long backoff3 = noJitterManager.calculateBackoffWithJitter(3);
        assertThat(backoff3).isEqualTo(4000L);

        // Fourth attempt: 1000 * 2^3 = 8000ms
        long backoff4 = noJitterManager.calculateBackoffWithJitter(4);
        assertThat(backoff4).isEqualTo(8000L);
    }

    @Test
    void shouldApplyJitterToBackoff() {
        // Create manager with high jitter for testing variance
        RetryManager jitterManager = new RetryManager(3, 1000L, 0.5, 2.0);

        // Collect multiple backoff calculations to verify jitter
        List<Long> backoffs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            backoffs.add(jitterManager.calculateBackoffWithJitter(1));
        }

        // With 50% jitter on 1000ms base, should range from ~500 to ~1500
        long min = backoffs.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = backoffs.stream().mapToLong(Long::longValue).max().orElse(0);

        // Verify there's variance (jitter is applied)
        assertThat(max - min).isGreaterThan(0);

        // Verify bounds are reasonable (within jitter range)
        assertThat(min).isGreaterThanOrEqualTo(500L);
        assertThat(max).isLessThanOrEqualTo(1500L);
    }

    @Test
    void shouldCapBackoffAtMaximum() {
        // Create manager with very high multiplier
        RetryManager manager = new RetryManager(10, 100000L, 0.0, 10.0);

        // After many attempts, should be capped at 10 minutes (600000ms)
        long backoff = manager.calculateBackoffWithJitter(5);

        assertThat(backoff).isLessThanOrEqualTo(600000L);
    }

    @Test
    void shouldHandleZeroMaxRetries() {
        RetryManager noRetryManager = new RetryManager(0, 1000L);

        Exception error = new RuntimeException("Test error");
        RetryDecision decision = noRetryManager.recordFailure("msg-1", error);

        // Should immediately route to DLQ when max retries is 0
        assertThat(decision.shouldRetry()).isFalse();
    }

    @Test
    void shouldTrackBackoffPeriod() {
        Exception error = new RuntimeException("Test error");
        RetryDecision decision = retryManager.recordFailure("msg-1", error);

        // Set next retry time
        retryManager.setNextRetryTime("msg-1", decision.getBackoffMs());

        // Should not be able to retry immediately
        assertThat(retryManager.canRetryNow("msg-1")).isFalse();
        assertThat(retryManager.getRemainingBackoffMs("msg-1")).isGreaterThan(0);
    }

    @Test
    void shouldAllowRetryAfterBackoffExpires() throws InterruptedException {
        RetryManager shortBackoffManager = new RetryManager(3, 10L, 0.0, 2.0);

        Exception error = new RuntimeException("Test error");
        RetryDecision decision = shortBackoffManager.recordFailure("msg-1", error);

        shortBackoffManager.setNextRetryTime("msg-1", decision.getBackoffMs());

        // Wait for backoff to expire
        Thread.sleep(15);

        assertThat(shortBackoffManager.canRetryNow("msg-1")).isTrue();
        assertThat(shortBackoffManager.getRemainingBackoffMs("msg-1")).isEqualTo(0);
    }

    @Test
    void shouldTrackMultipleMessagesSeparately() {
        Exception error1 = new RuntimeException("Error 1");
        Exception error2 = new RuntimeException("Error 2");

        retryManager.recordFailure("msg-1", error1);
        retryManager.recordFailure("msg-1", error1);
        retryManager.recordFailure("msg-2", error2);

        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(2);
        assertThat(retryManager.getRetryCount("msg-2")).isEqualTo(1);

        assertThat(retryManager.getLastError("msg-1")).isEqualTo(error1);
        assertThat(retryManager.getLastError("msg-2")).isEqualTo(error2);
    }

    @Test
    void shouldReportTrackedMessageCount() {
        Exception error = new RuntimeException("Test error");

        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(0);

        retryManager.recordFailure("msg-1", error);
        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(1);

        retryManager.recordFailure("msg-2", error);
        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(2);

        retryManager.clearRetryInfo("msg-1");
        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(1);
    }

    @Test
    void shouldClearAllRetryInfo() {
        Exception error = new RuntimeException("Test error");

        retryManager.recordFailure("msg-1", error);
        retryManager.recordFailure("msg-2", error);
        retryManager.recordFailure("msg-3", error);

        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(3);

        retryManager.clear();

        assertThat(retryManager.getTrackedMessageCount()).isEqualTo(0);
        assertThat(retryManager.getRetryCount("msg-1")).isEqualTo(0);
        assertThat(retryManager.getRetryCount("msg-2")).isEqualTo(0);
        assertThat(retryManager.getRetryCount("msg-3")).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 5, 10})
    void shouldRespectConfigurableMaxRetries(int maxRetries) {
        RetryManager manager = new RetryManager(maxRetries, 1000L);
        Exception error = new RuntimeException("Test error");

        // Should retry for all attempts up to max
        for (int i = 1; i < maxRetries; i++) {
            RetryDecision decision = manager.recordFailure("msg-1", error);
            assertThat(decision.shouldRetry()).isTrue()
                    .withFailMessage("Should retry on attempt %d/%d", i, maxRetries);
        }

        // Should route to DLQ on final attempt
        RetryDecision finalDecision = manager.recordFailure("msg-1", error);
        assertThat(finalDecision.shouldRetry()).isFalse();
        assertThat(finalDecision.getAttemptNumber()).isEqualTo(maxRetries);
    }

    @Test
    void shouldHandleNonExistentMessageIdGracefully() {
        assertThat(retryManager.getRetryCount("non-existent")).isEqualTo(0);
        assertThat(retryManager.getLastError("non-existent")).isNull();
        assertThat(retryManager.canRetryNow("non-existent")).isTrue();
        assertThat(retryManager.getRemainingBackoffMs("non-existent")).isEqualTo(0);

        // Should not throw on clearing non-existent
        retryManager.clearRetryInfo("non-existent");
    }

    @Test
    void shouldReturnCorrectDecisionFormat() {
        Exception error = new RuntimeException("Test error");
        RetryDecision retryDecision = retryManager.recordFailure("msg-1", error);

        String retryString = retryDecision.toString();
        assertThat(retryString).contains("msg-1");
        assertThat(retryString).contains("retry=true");
        assertThat(retryString).contains("attempt=1");
        assertThat(retryString).contains("backoffMs");

        // Exhaust retries
        retryManager.recordFailure("msg-1", error);
        RetryDecision dlqDecision = retryManager.recordFailure("msg-1", error);

        String dlqString = dlqDecision.toString();
        assertThat(dlqString).contains("msg-1");
        assertThat(dlqString).contains("retry=false");
        assertThat(dlqString).contains("routeToDLQ=true");
    }
}
