package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.MessageConverter;
import io.connect.sqs.fifo.DeduplicationTracker;
import io.connect.sqs.retry.RetryDecision;
import io.connect.sqs.retry.RetryManager;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Responsible for processing SQS messages, converting them to SourceRecords,
 * and handling retries/deduplication.
 */
public class MessageProcessor {
    private static final Logger log = LoggerFactory.getLogger(MessageProcessor.class);

    private final SqsSourceConnectorConfig config;
    private final MessageConverter messageConverter;
    private final RetryManager retryManager;
    private final DeduplicationTracker deduplicationTracker;
    private final DlqSender dlqSender;

    // Metrics references
    private final AtomicLong messagesFailed;
    private final AtomicLong messagesRetried;
    private final AtomicLong messagesDeduplicated;

    // State references
    private final Map<String, Message> pendingMessages;
    private final Map<String, Message> messagesInRetry;

    public MessageProcessor(
            SqsSourceConnectorConfig config,
            MessageConverter messageConverter,
            RetryManager retryManager,
            DeduplicationTracker deduplicationTracker,
            DlqSender dlqSender,
            AtomicLong messagesFailed,
            AtomicLong messagesRetried,
            AtomicLong messagesDeduplicated,
            Map<String, Message> pendingMessages,
            Map<String, Message> messagesInRetry) {
        this.config = config;
        this.messageConverter = messageConverter;
        this.retryManager = retryManager;
        this.deduplicationTracker = deduplicationTracker;
        this.dlqSender = dlqSender;
        this.messagesFailed = messagesFailed;
        this.messagesRetried = messagesRetried;
        this.messagesDeduplicated = messagesDeduplicated;
        this.pendingMessages = pendingMessages;
        this.messagesInRetry = messagesInRetry;
    }

    public List<SourceRecord> processMessages(List<Message> messages) {
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

        return records;
    }

    /**
     * Checks if a FIFO message should be skipped as a duplicate.
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

            // Don't delete or create DLQ record - let SQS visibility timeout handle
            // redelivery
            return null;

        } else {
            // Max retries exhausted, route to DLQ
            return handleFailedMessage(message, error, decision.getAttemptNumber());
        }
    }

    /**
     * Handle a failed message by optionally creating a DLQ record.
     */
    private SourceRecord handleFailedMessage(Message message, Exception error, int retryCount) {
        SourceRecord dlqRecord = dlqSender.createDlqRecord(message, error, retryCount);

        // Clean up retry tracking since we're done with this message
        retryManager.clearRetryInfo(message.messageId());
        messagesInRetry.remove(message.messageId());

        if (dlqRecord != null) {
            log.info("Sending failed message {} to DLQ topic: {} after {} retries",
                    message.messageId(), dlqRecord.topic(), retryCount);
        } else {
            // No DLQ configured, just log
            log.warn("No DLQ configured for failed message: {} after {} retries",
                    message.messageId(), retryCount);

            // Note: Deletion is handled by the caller adding to pendingMessages if we
            // return a record,
            // but here we return null so we need to handle deletion if configured.
            // HOWEVER, the logic in SqsSourceTask was:
            // if (dlqRecord != null) { records.add(dlqRecord); pendingMessages.put(...) }
            // else { if (delete) { deleteMessages(...) } }
            // So we need to replicate that behavior or change the contract.
            // Let's stick to returning the record if DLQ is enabled.
            // If DLQ is NOT enabled, we return null. The caller won't add anything to
            // records.
            // But we still need to delete the message if configured.

            // Wait, SqsSourceTask logic for no DLQ was:
            // if (config.isSqsDeleteMessages()) { sqsClient.deleteMessages(...) }
            // Since we don't have sqsClient here, we can't delete immediately.
            // We should probably return a special "Empty" record or handle this
            // differently.
            // OR we can just add it to pendingMessages and let the commit loop handle it?
            // But commit loop only deletes pendingMessages.
            // If we add it to pendingMessages but don't return a record, it will be deleted
            // on next commit.
            // That seems correct.

            if (config.isSqsDeleteMessages()) {
                pendingMessages.put(message.messageId(), message);
                log.debug("Marked failed message {} for deletion", message.messageId());
            }
        }

        return dlqRecord;
    }
}
