package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.fifo.DeduplicationTracker;
import io.connect.sqs.retry.RetryManager;
import io.connect.sqs.util.VersionUtil;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
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

    // Helpers
    private MessagePoller messagePoller;
    private MessageProcessor messageProcessor;
    private DlqSender dlqSender;

    // Track processed messages for commit
    private final Map<String, Message> pendingMessages = new ConcurrentHashMap<>();

    // Track messages pending retry (not visible in SQS yet due to visibility
    // timeout)
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
                    config.getRetryBackoffMs());

            // Initialize FIFO deduplication tracker if enabled
            if (config.isFifoQueue() && config.isSqsFifoDeduplicationEnabled()) {
                this.deduplicationTracker = new DeduplicationTracker(
                        config.getSqsFifoDeduplicationWindowMs());
                log.info("FIFO deduplication tracking enabled with window: {}ms",
                        config.getSqsFifoDeduplicationWindowMs());
            }

            // Initialize helpers
            this.messagePoller = new MessagePoller(sqsClient, config, messagesReceived, messagesDeleted);
            this.dlqSender = new DlqSender(config);
            this.messageProcessor = new MessageProcessor(
                    config, messageConverter, retryManager, deduplicationTracker, dlqSender,
                    messagesFailed, messagesRetried, messagesDeduplicated,
                    pendingMessages, messagesInRetry);

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
        try {
            // Delegate polling to MessagePoller
            List<Message> messages = messagePoller.poll();

            if (messages == null || messages.isEmpty()) {
                return null;
            }

            // Delegate processing to MessageProcessor
            List<SourceRecord> records = messageProcessor.processMessages(messages);

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
            MessageConverter baseConverter = (MessageConverter) converterClass.getDeclaredConstructor().newInstance();

            // Wrap with FieldExtractorConverter if field extraction is configured
            String extractFieldPath = config.getMessageOutputFieldExtract();
            if (extractFieldPath != null && !extractFieldPath.trim().isEmpty()) {
                log.info("Wrapping converter with FieldExtractorConverter for field path: {}", extractFieldPath);
                io.connect.sqs.converter.FieldExtractorConverter extractor = new io.connect.sqs.converter.FieldExtractorConverter();
                extractor.setDelegateConverter(baseConverter);
                return extractor;
            }

            return baseConverter;
        } catch (Exception e) {
            log.error("Failed to create message converter, using default", e);
            throw new ConnectException("Failed to create message converter", e);
        }
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
        if (messagePoller != null && messagePoller.getMessageFilterProcessor() != null
                && messagePoller.getMessageFilterProcessor().hasFilterPolicy()) {
            log.info("  Messages Filtered Out: {}", messagePoller.getMessageFilterProcessor().getMessagesFiltered());
            log.info("  Messages Passed Filter: {}", messagePoller.getMessageFilterProcessor().getMessagesPassed());
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
