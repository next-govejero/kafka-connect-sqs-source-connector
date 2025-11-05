package io.connect.sqs;

import io.connect.sqs.aws.SqsClient;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.util.VersionUtil;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

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

                    // Handle failed message
                    handleFailedMessage(message, e);
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

    private void handleFailedMessage(Message message, Exception error) {
        String dlqTopic = config.getDlqTopic();

        if (dlqTopic != null) {
            log.info("Sending failed message {} to DLQ topic: {}", message.messageId(), dlqTopic);
            // TODO: Implement DLQ publishing
            // For now, just log the error
        }

        // Delete failed message if configured
        if (config.isSqsDeleteMessages()) {
            try {
                sqsClient.deleteMessages(Collections.singletonList(message));
                log.debug("Deleted failed message {} from SQS", message.messageId());
            } catch (Exception e) {
                log.error("Failed to delete failed message from SQS", e);
            }
        }
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
