package io.connect.sqs.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Manages retry logic for failed message processing with exponential backoff and jitter.
 * Tracks retry attempts per message and determines when to route to DLQ.
 */
public class RetryManager {

    private static final Logger log = LoggerFactory.getLogger(RetryManager.class);

    // Tracks retry information for each message by message ID
    private final Map<String, RetryInfo> retryTracker = new ConcurrentHashMap<>();

    private final int maxRetries;
    private final long baseBackoffMs;
    private final double jitterFactor;
    private final double backoffMultiplier;

    /**
     * Creates a RetryManager with specified configuration.
     *
     * @param maxRetries Maximum number of retry attempts before routing to DLQ
     * @param baseBackoffMs Base backoff time in milliseconds
     */
    public RetryManager(int maxRetries, long baseBackoffMs) {
        this(maxRetries, baseBackoffMs, 0.3, 2.0);
    }

    /**
     * Creates a RetryManager with full configuration.
     *
     * @param maxRetries Maximum number of retry attempts
     * @param baseBackoffMs Base backoff time in milliseconds
     * @param jitterFactor Jitter factor (0.0 to 1.0) to randomize backoff
     * @param backoffMultiplier Multiplier for exponential backoff
     */
    public RetryManager(int maxRetries, long baseBackoffMs, double jitterFactor, double backoffMultiplier) {
        this.maxRetries = maxRetries;
        this.baseBackoffMs = baseBackoffMs;
        this.jitterFactor = Math.max(0.0, Math.min(1.0, jitterFactor));
        this.backoffMultiplier = backoffMultiplier;

        log.info("RetryManager initialized: maxRetries={}, baseBackoffMs={}, jitterFactor={}, backoffMultiplier={}",
                maxRetries, baseBackoffMs, jitterFactor, backoffMultiplier);
    }

    /**
     * Records a retry attempt for a message.
     *
     * @param messageId The SQS message ID
     * @param error The exception that caused the failure
     * @return RetryDecision indicating whether to retry or route to DLQ
     */
    public RetryDecision recordFailure(String messageId, Exception error) {
        RetryInfo info = retryTracker.compute(messageId, (id, existing) -> {
            if (existing == null) {
                return new RetryInfo(id, error);
            }
            existing.incrementAttempts(error);
            return existing;
        });

        int currentAttempt = info.getAttemptCount();

        if (currentAttempt >= maxRetries) {
            log.warn("Message {} exhausted {} retries, routing to DLQ", messageId, maxRetries);
            return RetryDecision.routeToDlq(messageId, currentAttempt);
        }

        long backoffMs = calculateBackoffWithJitter(currentAttempt);
        log.info("Message {} failed (attempt {}/{}), will retry after {}ms",
                messageId, currentAttempt, maxRetries, backoffMs);

        return RetryDecision.retry(messageId, currentAttempt, backoffMs);
    }

    /**
     * Calculates exponential backoff with jitter to prevent thundering herd.
     * Uses the formula: baseBackoff * (multiplier ^ attempt) * (1 - jitter + random(0, 2*jitter))
     *
     * @param attempt Current retry attempt number (1-based)
     * @return Backoff time in milliseconds with jitter applied
     */
    public long calculateBackoffWithJitter(int attempt) {
        // Exponential backoff: baseBackoff * (multiplier ^ (attempt - 1))
        long exponentialBackoff = (long) (baseBackoffMs * Math.pow(backoffMultiplier, attempt - 1));

        // Apply jitter: randomize within jitterFactor range
        // This helps prevent thundering herd when multiple messages fail simultaneously
        double jitterRange = exponentialBackoff * jitterFactor;
        double jitter = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterRange;

        long backoffWithJitter = Math.max(1, exponentialBackoff + (long) jitter);

        // Cap at a reasonable maximum (10 minutes)
        long maxBackoffMs = 600000L;
        return Math.min(backoffWithJitter, maxBackoffMs);
    }

    /**
     * Checks if a message should be retried now based on its backoff period.
     *
     * @param messageId The SQS message ID
     * @return true if the message can be retried now, false if still in backoff
     */
    public boolean canRetryNow(String messageId) {
        RetryInfo info = retryTracker.get(messageId);
        if (info == null) {
            return true; // No retry info means first attempt
        }
        return info.isBackoffExpired();
    }

    /**
     * Gets the current retry count for a message.
     *
     * @param messageId The SQS message ID
     * @return Number of retry attempts, 0 if not tracked
     */
    public int getRetryCount(String messageId) {
        RetryInfo info = retryTracker.get(messageId);
        return info != null ? info.getAttemptCount() : 0;
    }

    /**
     * Gets the last exception that caused failure for a message.
     *
     * @param messageId The SQS message ID
     * @return The last exception, or null if not tracked
     */
    public Exception getLastError(String messageId) {
        RetryInfo info = retryTracker.get(messageId);
        return info != null ? info.getLastError() : null;
    }

    /**
     * Clears retry tracking for a message (e.g., after successful processing or DLQ routing).
     *
     * @param messageId The SQS message ID
     */
    public void clearRetryInfo(String messageId) {
        RetryInfo removed = retryTracker.remove(messageId);
        if (removed != null) {
            log.debug("Cleared retry info for message {}", messageId);
        }
    }

    /**
     * Updates the next retry time for a message.
     *
     * @param messageId The SQS message ID
     * @param backoffMs The backoff period in milliseconds
     */
    public void setNextRetryTime(String messageId, long backoffMs) {
        RetryInfo info = retryTracker.get(messageId);
        if (info != null) {
            info.setNextRetryTime(System.currentTimeMillis() + backoffMs);
        }
    }

    /**
     * Gets the remaining time in backoff for a message.
     *
     * @param messageId The SQS message ID
     * @return Remaining backoff time in milliseconds, 0 if not in backoff
     */
    public long getRemainingBackoffMs(String messageId) {
        RetryInfo info = retryTracker.get(messageId);
        if (info == null) {
            return 0;
        }
        return Math.max(0, info.getNextRetryTime() - System.currentTimeMillis());
    }

    /**
     * Clears all retry tracking information.
     */
    public void clear() {
        retryTracker.clear();
        log.debug("Cleared all retry tracking information");
    }

    /**
     * Gets the number of messages currently being tracked for retry.
     *
     * @return Number of tracked messages
     */
    public int getTrackedMessageCount() {
        return retryTracker.size();
    }

    /**
     * Internal class to track retry information for a single message.
     */
    private static class RetryInfo {
        private final String messageId;
        private int attemptCount;
        private Exception lastError;
        private long firstFailureTime;
        private long lastFailureTime;
        private long nextRetryTime;

        public RetryInfo(String messageId, Exception error) {
            this.messageId = messageId;
            this.attemptCount = 1;
            this.lastError = error;
            this.firstFailureTime = System.currentTimeMillis();
            this.lastFailureTime = firstFailureTime;
            this.nextRetryTime = 0; // Will be set externally
        }

        public void incrementAttempts(Exception error) {
            this.attemptCount++;
            this.lastError = error;
            this.lastFailureTime = System.currentTimeMillis();
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public Exception getLastError() {
            return lastError;
        }

        public long getNextRetryTime() {
            return nextRetryTime;
        }

        public void setNextRetryTime(long nextRetryTime) {
            this.nextRetryTime = nextRetryTime;
        }

        public boolean isBackoffExpired() {
            return System.currentTimeMillis() >= nextRetryTime;
        }
    }
}
