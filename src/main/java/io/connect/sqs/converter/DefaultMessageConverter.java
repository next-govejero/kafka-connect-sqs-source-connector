package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.HashMap;
import java.util.Map;

/**
 * Default message converter that converts SQS messages to Kafka records.
 * Uses message body as the Kafka record value and includes SQS metadata as headers.
 */
public class DefaultMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DefaultMessageConverter.class);

    private static final String SOURCE_PARTITION_KEY = "queue_url";
    private static final String SOURCE_OFFSET_KEY = "message_id";

    // Header keys for SQS metadata
    private static final String HEADER_MESSAGE_ID = "sqs.message.id";
    private static final String HEADER_RECEIPT_HANDLE = "sqs.receipt.handle";
    private static final String HEADER_MD5_OF_BODY = "sqs.md5.of.body";
    private static final String HEADER_SENT_TIMESTAMP = "sqs.sent.timestamp";
    private static final String HEADER_APPROXIMATE_RECEIVE_COUNT = "sqs.approximate.receive.count";
    private static final String HEADER_APPROXIMATE_FIRST_RECEIVE_TIMESTAMP = "sqs.approximate.first.receive.timestamp";

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        log.debug("Converting SQS message: {}", message.messageId());

        // Create source partition (identifies the SQS queue)
        Map<String, String> sourcePartition = new HashMap<>();
        sourcePartition.put(SOURCE_PARTITION_KEY, config.getSqsQueueUrl());

        // Create source offset (identifies the message)
        Map<String, String> sourceOffset = new HashMap<>();
        sourceOffset.put(SOURCE_OFFSET_KEY, message.messageId());

        // Create headers with SQS metadata
        Headers headers = createHeaders(message, config);

        // Get Kafka topic and partition
        String topic = config.getKafkaTopic();
        Integer partition = config.getKafkaTopicPartition();

        // Message body as value
        String value = message.body();

        // Use message ID as key for partitioning
        String key = message.messageId();

        return new SourceRecord(
                sourcePartition,
                sourceOffset,
                topic,
                partition,
                Schema.STRING_SCHEMA,
                key,
                Schema.STRING_SCHEMA,
                value,
                System.currentTimeMillis(),
                headers
        );
    }

    private Headers createHeaders(Message message, SqsSourceConnectorConfig config) {
        ConnectHeaders headers = new ConnectHeaders();

        // Add basic message metadata
        headers.addString(HEADER_MESSAGE_ID, message.messageId());

        if (message.receiptHandle() != null) {
            headers.addString(HEADER_RECEIPT_HANDLE, message.receiptHandle());
        }

        if (message.md5OfBody() != null) {
            headers.addString(HEADER_MD5_OF_BODY, message.md5OfBody());
        }

        // Add system attributes
        Map<String, String> attributes = message.attributesAsStrings();
        if (attributes != null) {
            if (attributes.containsKey("SentTimestamp")) {
                headers.addString(HEADER_SENT_TIMESTAMP, attributes.get("SentTimestamp"));
            }

            if (attributes.containsKey("ApproximateReceiveCount")) {
                headers.addString(HEADER_APPROXIMATE_RECEIVE_COUNT,
                        attributes.get("ApproximateReceiveCount"));
            }

            if (attributes.containsKey("ApproximateFirstReceiveTimestamp")) {
                headers.addString(HEADER_APPROXIMATE_FIRST_RECEIVE_TIMESTAMP,
                        attributes.get("ApproximateFirstReceiveTimestamp"));
            }
        }

        // Add message attributes if enabled
        if (config.isSqsMessageAttributesEnabled() && message.hasMessageAttributes()) {
            addMessageAttributeHeaders(headers, message.messageAttributes());
        }

        return headers;
    }

    private void addMessageAttributeHeaders(ConnectHeaders headers, Map<String, MessageAttributeValue> attributes) {
        for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
            String headerKey = "sqs.message.attribute." + entry.getKey();
            MessageAttributeValue value = entry.getValue();

            String dataType = value.dataType();
            String stringValue = value.stringValue();

            if (stringValue != null) {
                headers.addString(headerKey, stringValue);
            } else if (value.binaryValue() != null) {
                headers.addBytes(headerKey, value.binaryValue().asByteArray());
            }

            // Add data type as separate header
            headers.addString(headerKey + ".type", dataType);
        }
    }
}
