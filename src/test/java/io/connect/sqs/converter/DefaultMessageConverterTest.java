package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMessageConverterTest {

    private DefaultMessageConverter converter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new DefaultMessageConverter();
        config = new SqsSourceConnectorConfig(getTestConfig());
    }

    @Test
    void shouldConvertBasicMessage() {
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test message body")
                .md5OfBody("abc123")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.key()).isEqualTo("test-message-id");
        assertThat(record.value()).isEqualTo("test message body");
        assertThat(record.keySchema()).isEqualTo(Schema.STRING_SCHEMA);
        assertThat(record.valueSchema()).isEqualTo(Schema.STRING_SCHEMA);
    }

    @Test
    void shouldIncludeBasicHeaders() {
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .md5OfBody("abc123")
                .receiptHandle("test-receipt-handle")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.headers()).isNotNull();

        Header messageIdHeader = getHeader(record, "sqs.message.id");
        assertThat(messageIdHeader).isNotNull();
        assertThat(messageIdHeader.value()).isEqualTo("test-message-id");

        Header md5Header = getHeader(record, "sqs.md5.of.body");
        assertThat(md5Header).isNotNull();
        assertThat(md5Header.value()).isEqualTo("abc123");

        Header receiptHeader = getHeader(record, "sqs.receipt.handle");
        assertThat(receiptHeader).isNotNull();
        assertThat(receiptHeader.value()).isEqualTo("test-receipt-handle");
    }

    @Test
    void shouldIncludeSystemAttributes() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("SentTimestamp", "1234567890");
        attributes.put("ApproximateReceiveCount", "1");
        attributes.put("ApproximateFirstReceiveTimestamp", "1234567890");

        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        Header sentTimestampHeader = getHeader(record, "sqs.sent.timestamp");
        assertThat(sentTimestampHeader).isNotNull();
        assertThat(sentTimestampHeader.value()).isEqualTo("1234567890");

        Header receiveCountHeader = getHeader(record, "sqs.approximate.receive.count");
        assertThat(receiveCountHeader).isNotNull();
        assertThat(receiveCountHeader.value()).isEqualTo("1");

        Header firstReceiveHeader = getHeader(record, "sqs.approximate.first.receive.timestamp");
        assertThat(firstReceiveHeader).isNotNull();
        assertThat(firstReceiveHeader.value()).isEqualTo("1234567890");
    }

    @Test
    void shouldIncludeMessageAttributes() {
        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        messageAttributes.put("customAttribute",
                MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue("custom-value")
                        .build());

        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .messageAttributes(messageAttributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        Header customAttrHeader = getHeader(record, "sqs.message.attribute.customAttribute");
        assertThat(customAttrHeader).isNotNull();
        assertThat(customAttrHeader.value()).isEqualTo("custom-value");

        Header typeHeader = getHeader(record, "sqs.message.attribute.customAttribute.type");
        assertThat(typeHeader).isNotNull();
        assertThat(typeHeader.value()).isEqualTo("String");
    }

    @Test
    void shouldUseSpecificPartitionWhenConfigured() {
        Map<String, String> configProps = getTestConfig();
        configProps.put("kafka.topic.partition", "3");
        SqsSourceConnectorConfig configWithPartition = new SqsSourceConnectorConfig(configProps);

        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .build();

        SourceRecord record = converter.convert(sqsMessage, configWithPartition);

        assertThat(record.kafkaPartition()).isEqualTo(3);
    }

    @Test
    void shouldHaveNullPartitionWhenNotConfigured() {
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.kafkaPartition()).isNull();
    }

    @Test
    void shouldIncludeSourcePartitionAndOffset() {
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("test body")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.sourcePartition()).containsKey("queue_url");
        assertThat(record.sourcePartition().get("queue_url"))
                .isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");

        assertThat(record.sourceOffset()).containsKey("message_id");
        assertThat(record.sourceOffset().get("message_id")).isEqualTo("test-message-id");
    }

    @Test
    void shouldUseMessageGroupIdAsKeyForFifoQueue() {
        // Configure FIFO queue
        Map<String, String> configProps = getFifoTestConfig();
        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(configProps);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "order-123");
        attributes.put("MessageDeduplicationId", "dedup-abc");
        attributes.put("SequenceNumber", "100");

        Message sqsMessage = Message.builder()
                .messageId("fifo-message-id")
                .body("fifo message body")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        // Key should be MessageGroupId for FIFO queues to preserve ordering
        assertThat(record.key()).isEqualTo("order-123");
        assertThat(record.value()).isEqualTo("fifo message body");
    }

    @Test
    void shouldIncludeFifoSpecificHeaders() {
        Map<String, String> configProps = getFifoTestConfig();
        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(configProps);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "order-123");
        attributes.put("MessageDeduplicationId", "dedup-abc");
        attributes.put("SequenceNumber", "100");

        Message sqsMessage = Message.builder()
                .messageId("fifo-message-id")
                .body("fifo message body")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        Header messageGroupHeader = getHeader(record, "sqs.message.group.id");
        assertThat(messageGroupHeader).isNotNull();
        assertThat(messageGroupHeader.value()).isEqualTo("order-123");

        Header deduplicationHeader = getHeader(record, "sqs.message.deduplication.id");
        assertThat(deduplicationHeader).isNotNull();
        assertThat(deduplicationHeader.value()).isEqualTo("dedup-abc");

        Header sequenceHeader = getHeader(record, "sqs.sequence.number");
        assertThat(sequenceHeader).isNotNull();
        assertThat(sequenceHeader.value()).isEqualTo("100");
    }

    @Test
    void shouldFallbackToMessageIdWhenMessageGroupIdMissing() {
        Map<String, String> configProps = getFifoTestConfig();
        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(configProps);

        Message sqsMessage = Message.builder()
                .messageId("fifo-message-id")
                .body("fifo message body")
                .build();

        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        // Should fallback to message ID when MessageGroupId is not available
        assertThat(record.key()).isEqualTo("fifo-message-id");
    }

    @Test
    void shouldAutoDetectFifoQueueFromUrl() {
        // Config with .fifo suffix in queue URL
        Map<String, String> configProps = getTestConfig();
        configProps.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue.fifo");
        SqsSourceConnectorConfig autoDetectConfig = new SqsSourceConnectorConfig(configProps);

        assertThat(autoDetectConfig.isFifoQueue()).isTrue();

        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "auto-detected-group");

        Message sqsMessage = Message.builder()
                .messageId("fifo-message-id")
                .body("fifo message body")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, autoDetectConfig);

        // Should use MessageGroupId as key due to auto-detection
        assertThat(record.key()).isEqualTo("auto-detected-group");
    }

    @Test
    void shouldNotUseMessageGroupIdForStandardQueue() {
        // Standard queue (not FIFO)
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "should-not-be-used");

        Message sqsMessage = Message.builder()
                .messageId("standard-message-id")
                .body("standard message body")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        // Key should be message ID for standard queues
        assertThat(record.key()).isEqualTo("standard-message-id");
    }

    @Test
    void shouldPreserveOrderingWithinMessageGroup() {
        Map<String, String> configProps = getFifoTestConfig();
        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(configProps);

        // All messages with same MessageGroupId should have same key
        // This ensures they go to the same Kafka partition
        for (int i = 1; i <= 3; i++) {
            Map<String, String> attributes = new HashMap<>();
            attributes.put("MessageGroupId", "order-group-1");
            attributes.put("SequenceNumber", String.valueOf(i * 100));

            Message sqsMessage = Message.builder()
                    .messageId("msg-" + i)
                    .body("message " + i)
                    .attributesWithStrings(attributes)
                    .build();

            SourceRecord record = converter.convert(sqsMessage, fifoConfig);
            assertThat(record.key()).isEqualTo("order-group-1");
        }
    }

    private Header getHeader(SourceRecord record, String key) {
        for (Header header : record.headers()) {
            if (header.key().equals(key)) {
                return header;
            }
        }
        return null;
    }

    private Map<String, String> getTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        config.put("kafka.topic", "test-topic");
        config.put("aws.region", "us-east-1");
        config.put("sasl.mechanism", "SCRAM-SHA-512");
        config.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return config;
    }

    private Map<String, String> getFifoTestConfig() {
        Map<String, String> config = getTestConfig();
        config.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue.fifo");
        config.put("sqs.fifo.queue", "true");
        return config;
    }
}

