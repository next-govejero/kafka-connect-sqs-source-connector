package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.fifo.DeduplicationTracker;
import io.connect.sqs.filter.MessageFilterProcessor;
import io.connect.sqs.retry.RetryDecision;
import io.connect.sqs.retry.RetryManager;
import io.connect.sqs.util.VersionUtil;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQS Source Task implementation.
 * Polls messages from SQS and produces them to Kafka.
 */
public class SqsSourceTask extends SourceTask {

    private static final Logger log = LoggerFactory.getLogger(SqsSourceTask.class);

    private SqsSourceConnectorConfig config;
    private SqsClient sqsClient;
    private MessageConverter messageConverter;
    private RetryManager retryManager;
    private DeduplicationTracker deduplicationTracker;
    private MessageFilterProcessor messageFilterProcessor;

    // Track processed messages for commit
    private final Map<String, Message> pendingMessages = new ConcurrentHashMap<>();

    // Track messages pending retry (not visible in SQS yet due to visibility timeout)
    private final Map<String, Message> messagesInRetry = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesDeleted = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private final AtomicLong messagesRetried = new AtomicLong(0);
    private final AtomicLong messagesDeduplicated = new AtomicLong(0);

    @Override
    public String version() {
        return VersionUtil.getVersion();
    }

    @Override
    public void start(Map<String, String> props) {
        log.info("Starting SQS Source Task");

        try {
            this.config = new SqsSourceConnectorConfig(props);

            // Initialize SQS client
            this.sqsClient = new SqsClient(config);

            // Initialize message converter
            this.messageConverter = createMessageConverter();

            // Initialize retry manager with exponential backoff
            this.retryManager = new RetryManager(
                    config.getMaxRetries(),
                    config.getRetryBackoffMs()
            );

            // Initialize FIFO deduplication tracker if enabled
            if (config.isFifoQueue() && config.isSqsFifoDeduplicationEnabled()) {
                this.deduplicationTracker = new DeduplicationTracker(
                        config.getSqsFifoDeduplicationWindowMs()
                );
                log.info("FIFO deduplication tracking enabled with window: {}ms",
                        config.getSqsFifoDeduplicationWindowMs());
            }

            // Initialize message filter processor
            this.messageFilterProcessor = new MessageFilterProcessor(
                    config.getMessageFilterPolicy()
            );
            if (messageFilterProcessor.hasFilterPolicy()) {
                log.info("Message filtering enabled with policy: {}", config.getMessageFilterPolicy());
            }

            log.info("SQS Source Task started successfully");
            log.info("Polling from queue: {}", config.getSqsQueueUrl());
            log.info("Publishing to topic: {}", config.getKafkaTopic());
            log.info("Retry configuration: maxRetries={}, baseBackoffMs={}",
                    config.getMaxRetries(), config.getRetryBackoffMs());

            if (config.isFifoQueue()) {
                log.info("FIFO queue mode enabled - ordering will be preserved using MessageGroupId");
            }

        } catch (Exception e) {
            log.error("Failed to start SQS Source Task", e);
            throw new ConnectException("Failed to start SQS Source Task", e);
        }
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        log.debug("Polling for messages from SQS");

        try {
            // Receive messages from SQS
            List<Message> messages = sqsClient.receiveMessages();

            if (messages.isEmpty()) {
                log.trace("No messages received from SQS");
                return null;
            }

            messagesReceived.addAndGet(messages.size());
            log.debug("Received {} messages from SQS", messages.size());

            // Apply message filtering if configured
            List<Message> filteredOutMessages = new ArrayList<>();
            if (messageFilterProcessor.hasFilterPolicy()) {
                List<Message> matchingMessages = messageFilterProcessor.filter(messages);
                // Track filtered-out messages for deletion
                for (Message msg : messages) {
                    if (!matchingMessages.contains(msg)) {
                        filteredOutMessages.add(msg);
                    }
                }
                messages = matchingMessages;

                // Delete filtered-out messages immediately
                if (config.isSqsDeleteMessages() && !filteredOutMessages.isEmpty()) {
                    try {
                        sqsClient.deleteMessages(filteredOutMessages);
                        messagesDeleted.addAndGet(filteredOutMessages.size());
                        log.debug("Deleted {} filtered-out messages from SQS", filteredOutMessages.size());
                    } catch (Exception e) {
                        log.error("Failed to delete filtered-out messages from SQS", e);
                    }
                }
            }

            if (messages.isEmpty()) {
                log.trace("All messages were filtered out");
                return null;
            }

            List<SourceRecord> records = new ArrayList<>();

            for (Message message : messages) {
                try {
                    // Check if message is still in backoff period
                    if (!retryManager.canRetryNow(message.messageId())) {
                        long remainingMs = retryManager.getRemainingBackoffMs(message.messageId());
                        log.debug("Message {} still in backoff, {}ms remaining",
                                message.messageId(), remainingMs);
                        // Don't process yet, let SQS visibility timeout handle redelivery
                        continue;
                    }

                    // Check for FIFO duplicates
                    if (shouldSkipAsDuplicate(message)) {
                        messagesDeduplicated.incrementAndGet();
                        // Delete duplicate message from SQS
                        if (config.isSqsDeleteMessages()) {
                            pendingMessages.put(message.messageId(), message);
                        }
                        continue;
                    }

                    SourceRecord record = messageConverter.convert(message, config);
                    records.add(record);

                    // Track pending message for later deletion
                    pendingMessages.put(message.messageId(), message);

                    // Clear retry tracking for successfully processed message
                    retryManager.clearRetryInfo(message.messageId());
                    messagesInRetry.remove(message.messageId());

                } catch (Exception e) {
                    log.error("Failed to convert SQS message: {}", message.messageId(), e);
                    messagesFailed.incrementAndGet();

                    // Handle failed message with retry logic
                    SourceRecord dlqRecord = handleFailedMessageWithRetry(message, e);
                    if (dlqRecord != null) {
                        records.add(dlqRecord);
                        // Track DLQ message for deletion too
                        pendingMessages.put(message.messageId(), message);
                    }
                }
            }

            if (!records.isEmpty()) {
                messagesSent.addAndGet(records.size());
                log.debug("Created {} source records for Kafka", records.size());
            }

            // Log metrics periodically
            if (messagesReceived.get() % 100 == 0) {
                logMetrics();
            }

            return records.isEmpty() ? null : records;

        } catch (Exception e) {
            log.error("Error polling messages from SQS", e);
            throw new ConnectException("Error polling messages from SQS", e);
        }
    }

    @Override
    public void commit() throws InterruptedException {
        log.debug("Committing processed messages");

        if (config.isSqsDeleteMessages() && !pendingMessages.isEmpty()) {
            try {
                List<Message> toDelete = new ArrayList<>(pendingMessages.values());
                sqsClient.deleteMessages(toDelete);

                messagesDeleted.addAndGet(toDelete.size());
                log.debug("Deleted {} messages from SQS", toDelete.size());

                pendingMessages.clear();

            } catch (Exception e) {
                log.error("Failed to delete messages from SQS", e);
                // Don't throw exception here to avoid stopping the connector
                // Messages will become visible again after visibility timeout
            }
        } else {
            pendingMessages.clear();
        }
    }

    @Override
    public void commitRecord(SourceRecord record) throws InterruptedException {
        // Individual record commit - called before commit()
        log.trace("Committing individual record: {}", record);
    }

    @Override
    public void stop() {
        log.info("Stopping SQS Source Task");

        logMetrics();

        if (sqsClient != null) {
            try {
                sqsClient.close();
            } catch (Exception e) {
                log.error("Error closing SQS client", e);
            }
        }

        pendingMessages.clear();
        messagesInRetry.clear();
        if (retryManager != null) {
            retryManager.clear();
        }
        if (deduplicationTracker != null) {
            deduplicationTracker.clear();
        }
        log.info("SQS Source Task stopped");
    }

    private MessageConverter createMessageConverter() {
        try {
            String converterClassName = config.getMessageConverterClass();
            Class<?> converterClass = Class.forName(converterClassName);
            return (MessageConverter) converterClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            log.error("Failed to create message converter, using default", e);
            throw new ConnectException("Failed to create message converter", e);
        }
    }

    /**
     * Checks if a FIFO message should be skipped as a duplicate.
     *
     * @param message The SQS message
     * @return true if the message is a duplicate and should be skipped
     */
    private boolean shouldSkipAsDuplicate(Message message) {
        if (deduplicationTracker == null) {
            return false;
        }

        Map<String, String> attributes = message.attributesAsStrings();
        if (attributes == null) {
            return false;
        }

        String deduplicationId = attributes.get("MessageDeduplicationId");
        if (deduplicationId != null && deduplicationTracker.isDuplicate(deduplicationId)) {
            log.info("Skipping duplicate FIFO message {} with deduplication ID: {}",
                    message.messageId(), deduplicationId);
            return true;
        }

        return false;
    }

    /**
     * Handle a failed message with retry logic before routing to DLQ.
     * Implements exponential backoff with jitter to prevent thundering herd.
     *
     * @param message The SQS message that failed processing
     * @param error The exception that occurred
     * @return A SourceRecord for the DLQ topic if max retries exhausted, null if retrying
     */
    private SourceRecord handleFailedMessageWithRetry(Message message, Exception error) {
        // Record the failure and get retry decision
        RetryDecision decision = retryManager.recordFailure(message.messageId(), error);

        if (decision.shouldRetry()) {
            // Schedule for retry with backoff
            retryManager.setNextRetryTime(message.messageId(), decision.getBackoffMs());
            messagesInRetry.put(message.messageId(), message);
            messagesRetried.incrementAndGet();

            log.info("Message {} scheduled for retry (attempt {}/{}) after {}ms backoff",
                    message.messageId(), decision.getAttemptNumber(), config.getMaxRetries(),
                    decision.getBackoffMs());

            // Don't delete or create DLQ record - let SQS visibility timeout handle redelivery
            return null;

        } else {
            // Max retries exhausted, route to DLQ
            return handleFailedMessage(message, error, decision.getAttemptNumber());
        }
    }

    /**
     * Handle a failed message by optionally creating a DLQ record.
     *
     * @param message The SQS message that failed processing
     * @param error The exception that occurred
     * @param retryCount The number of retry attempts made
     * @return A SourceRecord for the DLQ topic if configured, null otherwise
     */
    private SourceRecord handleFailedMessage(Message message, Exception error, int retryCount) {
        String dlqTopic = config.getDlqTopic();
        SourceRecord dlqRecord = null;

        // Clean up retry tracking since we're done with this message
        retryManager.clearRetryInfo(message.messageId());
        messagesInRetry.remove(message.messageId());

        if (dlqTopic != null && !dlqTopic.trim().isEmpty()) {
            log.info("Sending failed message {} to DLQ topic: {} after {} retries",
                    message.messageId(), dlqTopic, retryCount);

            // Create DLQ record with original message and error information
            dlqRecord = createDlqRecord(message, error, dlqTopic, retryCount);

        } else {
            // No DLQ configured, just log and optionally delete
            log.warn("No DLQ configured for failed message: {} after {} retries",
                    message.messageId(), retryCount);

            // Delete failed message if configured and no DLQ
            if (config.isSqsDeleteMessages()) {
                try {
                    sqsClient.deleteMessages(Collections.singletonList(message));
                    log.debug("Deleted failed message {} from SQS", message.messageId());
                } catch (Exception e) {
                    log.error("Failed to delete failed message from SQS", e);
                }
            }
        }

        return dlqRecord;
    }

    /**
     * Handle a failed message by optionally creating a DLQ record.
     * Legacy method for backward compatibility.
     *
     * @param message The SQS message that failed processing
     * @param error The exception that occurred
     * @return A SourceRecord for the DLQ topic if configured, null otherwise
     */
    private SourceRecord handleFailedMessage(Message message, Exception error) {
        return handleFailedMessage(message, error, 0);
    }

    /**
     * Create a DLQ SourceRecord with the failed message and error information.
     *
     * @param message The SQS message that failed
     * @param error The exception that occurred
     * @param dlqTopic The DLQ topic name
     * @param retryCount The number of retry attempts made
     * @return A SourceRecord for the DLQ topic
     */
    private SourceRecord createDlqRecord(Message message, Exception error, String dlqTopic, int retryCount) {
        // Create source partition
        Map<String, String> sourcePartition = new HashMap<>();
        sourcePartition.put("queue_url", config.getSqsQueueUrl());

        // Create source offset
        Map<String, String> sourceOffset = new HashMap<>();
        sourceOffset.put("message_id", message.messageId());
        sourceOffset.put("dlq", "true");

        // Create headers with error information
        ConnectHeaders headers = new ConnectHeaders();
        headers.addString("sqs.message.id", message.messageId());
        headers.addString("sqs.queue.url", config.getSqsQueueUrl());
        headers.addString("error.class", error.getClass().getName());
        headers.addString("error.message", error.getMessage() != null ? error.getMessage() : "");
        headers.addString("error.stacktrace", getStackTrace(error));
        headers.addLong("error.timestamp", System.currentTimeMillis());

        // Add retry information headers
        headers.addInt("retry.count", retryCount);
        headers.addInt("retry.max", config.getMaxRetries());
        headers.addBoolean("retry.exhausted", retryCount >= config.getMaxRetries());

        // Add SQS message attributes if present
        if (message.md5OfBody() != null) {
            headers.addString("sqs.md5.of.body", message.md5OfBody());
        }

        // Create the DLQ record with the original message body
        String key = message.messageId();
        String value = message.body();

        return new SourceRecord(
                sourcePartition,
                sourceOffset,
                dlqTopic,
                null, // Let Kafka partition
                Schema.STRING_SCHEMA,
                key,
                Schema.STRING_SCHEMA,
                value,
                System.currentTimeMillis(),
                headers
        );
    }

    /**
     * Create a DLQ SourceRecord with the failed message and error information.
     * Legacy method for backward compatibility.
     *
     * @param message The SQS message that failed
     * @param error The exception that occurred
     * @param dlqTopic The DLQ topic name
     * @return A SourceRecord for the DLQ topic
     */
    private SourceRecord createDlqRecord(Message message, Exception error, String dlqTopic) {
        return createDlqRecord(message, error, dlqTopic, 0);
    }

    /**
     * Get the stack trace from an exception as a string.
     *
     * @param error The exception
     * @return The stack trace as a string
     */
    private String getStackTrace(Exception error) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        String stackTrace = sw.toString();
        // Truncate if too long (max 8KB for header value)
        if (stackTrace.length() > 8000) {
            stackTrace = stackTrace.substring(0, 8000) + "... (truncated)";
        }
        return stackTrace;
    }

    private void logMetrics() {
        log.info("SQS Source Task Metrics:");
        log.info("  Messages Received: {}", messagesReceived.get());
        log.info("  Messages Sent to Kafka: {}", messagesSent.get());
        log.info("  Messages Deleted: {}", messagesDeleted.get());
        log.info("  Messages Failed: {}", messagesFailed.get());
        log.info("  Messages Retried: {}", messagesRetried.get());
        log.info("  Messages Deduplicated: {}", messagesDeduplicated.get());
        log.info("  Pending Messages: {}", pendingMessages.size());
        log.info("  Messages In Retry: {}", messagesInRetry.size());
        if (deduplicationTracker != null) {
            log.info("  Deduplication Tracker Size: {}", deduplicationTracker.getTrackedCount());
        }
        if (messageFilterProcessor != null && messageFilterProcessor.hasFilterPolicy()) {
            log.info("  Messages Filtered Out: {}", messageFilterProcessor.getMessagesFiltered());
            log.info("  Messages Passed Filter: {}", messageFilterProcessor.getMessagesPassed());
        }
    }

    /**
     * Gets the retry manager for testing purposes.
     *
     * @return The retry manager instance
     */
    RetryManager getRetryManager() {
        return retryManager;
    }

    /**
     * Sets the retry manager for testing purposes.
     *
     * @param retryManager The retry manager to use
     */
    void setRetryManager(RetryManager retryManager) {
        this.retryManager = retryManager;
    }
}
