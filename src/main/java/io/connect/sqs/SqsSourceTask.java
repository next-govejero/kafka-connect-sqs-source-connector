package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
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

    // Track processed messages for commit
    private final Map<String, Message> pendingMessages = new ConcurrentHashMap<>();

    // Metrics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesDeleted = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

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

            log.info("SQS Source Task started successfully");
            log.info("Polling from queue: {}", config.getSqsQueueUrl());
            log.info("Publishing to topic: {}", config.getKafkaTopic());

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

            List<SourceRecord> records = new ArrayList<>();

            for (Message message : messages) {
                try {
                    SourceRecord record = messageConverter.convert(message, config);
                    records.add(record);

                    // Track pending message for later deletion
                    pendingMessages.put(message.messageId(), message);

                } catch (Exception e) {
                    log.error("Failed to convert SQS message: {}", message.messageId(), e);
                    messagesFailed.incrementAndGet();

                    // Handle failed message (may create DLQ record)
                    SourceRecord dlqRecord = handleFailedMessage(message, e);
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
     * Handle a failed message by optionally creating a DLQ record.
     *
     * @param message The SQS message that failed processing
     * @param error The exception that occurred
     * @return A SourceRecord for the DLQ topic if configured, null otherwise
     */
    private SourceRecord handleFailedMessage(Message message, Exception error) {
        String dlqTopic = config.getDlqTopic();
        SourceRecord dlqRecord = null;

        if (dlqTopic != null && !dlqTopic.trim().isEmpty()) {
            log.info("Sending failed message {} to DLQ topic: {}", message.messageId(), dlqTopic);

            // Create DLQ record with original message and error information
            dlqRecord = createDlqRecord(message, error, dlqTopic);

        } else {
            // No DLQ configured, just log and optionally delete
            log.warn("No DLQ configured for failed message: {}", message.messageId());

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
     * Create a DLQ SourceRecord with the failed message and error information.
     *
     * @param message The SQS message that failed
     * @param error The exception that occurred
     * @param dlqTopic The DLQ topic name
     * @return A SourceRecord for the DLQ topic
     */
    private SourceRecord createDlqRecord(Message message, Exception error, String dlqTopic) {
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
        log.info("  Pending Messages: {}", pendingMessages.size());
    }
}
