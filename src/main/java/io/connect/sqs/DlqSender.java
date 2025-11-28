package io.connect.sqs;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.source.SourceRecord;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Responsible for creating Dead Letter Queue (DLQ) records.
 */
public class DlqSender {

    private final SqsSourceConnectorConfig config;

    public DlqSender(SqsSourceConnectorConfig config) {
        this.config = config;
    }

    /**
     * Create a DLQ SourceRecord with the failed message and error information.
     *
     * @param message    The SQS message that failed
     * @param error      The exception that occurred
     * @param retryCount The number of retry attempts made
     * @return A SourceRecord for the DLQ topic
     */
    public SourceRecord createDlqRecord(Message message, Exception error, int retryCount) {
        String dlqTopic = config.getDlqTopic();
        if (dlqTopic == null || dlqTopic.trim().isEmpty()) {
            return null;
        }

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
                headers);
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
}
