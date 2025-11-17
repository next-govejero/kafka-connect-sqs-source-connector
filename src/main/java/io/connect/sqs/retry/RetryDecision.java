package io.connect.sqs.retry;

/**
 * Represents a retry decision for a failed message.
 * Indicates whether the message should be retried or routed to the DLQ.
 */
public class RetryDecision {

    private final String messageId;
    private final boolean shouldRetry;
    private final int attemptNumber;
    private final long backoffMs;

    private RetryDecision(String messageId, boolean shouldRetry, int attemptNumber, long backoffMs) {
        this.messageId = messageId;
        this.shouldRetry = shouldRetry;
        this.attemptNumber = attemptNumber;
        this.backoffMs = backoffMs;
    }

    /**
     * Creates a retry decision indicating the message should be retried.
     *
     * @param messageId The SQS message ID
     * @param attemptNumber The current attempt number
     * @param backoffMs The backoff time in milliseconds before retry
     * @return RetryDecision for retry
     */
    public static RetryDecision retry(String messageId, int attemptNumber, long backoffMs) {
        return new RetryDecision(messageId, true, attemptNumber, backoffMs);
    }

    /**
     * Creates a retry decision indicating the message should be routed to DLQ.
     *
     * @param messageId The SQS message ID
     * @param finalAttemptNumber The total number of attempts made
     * @return RetryDecision for DLQ routing
     */
    public static RetryDecision routeToDlq(String messageId, int finalAttemptNumber) {
        return new RetryDecision(messageId, false, finalAttemptNumber, 0);
    }

    /**
     * @return The SQS message ID
     */
    public String getMessageId() {
        return messageId;
    }

    /**
     * @return true if the message should be retried, false if it should go to DLQ
     */
    public boolean shouldRetry() {
        return shouldRetry;
    }

    /**
     * @return The current attempt number (1-based)
     */
    public int getAttemptNumber() {
        return attemptNumber;
    }

    /**
     * @return The backoff time in milliseconds (only relevant if shouldRetry is true)
     */
    public long getBackoffMs() {
        return backoffMs;
    }

    @Override
    public String toString() {
        if (shouldRetry) {
            return String.format("RetryDecision{messageId='%s', retry=true, attempt=%d, backoffMs=%d}",
                    messageId, attemptNumber, backoffMs);
        } else {
            return String.format("RetryDecision{messageId='%s', retry=false, totalAttempts=%d, routeToDLQ=true}",
                    messageId, attemptNumber);
        }
    }
}
