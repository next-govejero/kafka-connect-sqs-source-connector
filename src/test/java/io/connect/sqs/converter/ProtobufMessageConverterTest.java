package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtobufMessageConverterTest {

    private ProtobufMessageConverter converter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new ProtobufMessageConverter();
        config = new SqsSourceConnectorConfig(getTestConfig());
    }

    @Test
    void shouldConvertSimpleJsonToProtobuf() {
        String jsonBody = "{\"name\":\"John\",\"age\":30,\"active\":true}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .md5OfBody("abc123")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.key()).isEqualTo("test-message-id");
        assertThat(record.keySchema()).isEqualTo(Schema.STRING_SCHEMA);

        // Value should be a Struct
        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("name")).isEqualTo("John");
        assertThat(value.get("age")).isEqualTo(30);
        assertThat(value.get("active")).isEqualTo(true);
    }

    @Test
    void shouldConvertJsonWithDifferentTypes() {
        String jsonBody = "{\"intField\":42,\"longField\":9999999999,\"doubleField\":3.14}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("intField")).isEqualTo(42);
        assertThat(value.get("longField")).isEqualTo(9999999999L);
        assertThat(value.get("doubleField")).isEqualTo(3.14);
    }

    @Test
    void shouldConvertJsonWithRepeatedField() {
        String jsonBody = "{\"tags\":[\"tag1\",\"tag2\",\"tag3\"],\"count\":3}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();

        Object tagsObj = value.get("tags");
        assertThat(tagsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) tagsObj;
        assertThat(tags).containsExactly("tag1", "tag2", "tag3");
    }

    @Test
    void shouldConvertNonJsonMessageAsString() {
        String plainText = "Not valid JSON content";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(plainText)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isEqualTo(plainText);
        assertThat(record.valueSchema()).isEqualTo(Schema.STRING_SCHEMA);
    }

    @Test
    void shouldIncludeSchemaTypeHeader() {
        String jsonBody = "{\"key\":\"value\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        Header schemaTypeHeader = getHeader(record, "sqs.schema.type");
        assertThat(schemaTypeHeader).isNotNull();
        assertThat(schemaTypeHeader.value()).isEqualTo("Protobuf");
    }

    @Test
    void shouldUseFifoMessageGroupIdAsKey() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "customer-456");
        attributes.put("MessageDeduplicationId", "dedup-xyz");

        String jsonBody = "{\"customerId\":\"456\",\"action\":\"purchase\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .attributesWithStrings(attributes)
                .build();

        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(getFifoTestConfig());
        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        assertThat(record.key()).isEqualTo("customer-456");
    }

    @Test
    void shouldHandleEmptyJsonObject() {
        String jsonBody = "{}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
    }

    @Test
    void shouldConvertJsonWithBooleans() {
        String jsonBody = "{\"isActive\":true,\"isDeleted\":false}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("isActive")).isEqualTo(true);
        assertThat(value.get("isDeleted")).isEqualTo(false);
    }

    @Test
    void shouldIncludeSourcePartitionAndOffset() {
        String jsonBody = "{\"data\":\"test\"}";
        Message sqsMessage = Message.builder()
                .messageId("proto-msg-123")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.sourcePartition()).containsKey("queue_url");
        assertThat(record.sourceOffset()).containsKey("message_id");
        assertThat(record.sourceOffset().get("message_id")).isEqualTo("proto-msg-123");
    }

    @Test
    void shouldIncludeSqsMetadataHeaders() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("SentTimestamp", "1234567890");
        attributes.put("ApproximateReceiveCount", "2");

        String jsonBody = "{\"key\":\"value\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .receiptHandle("test-receipt-handle")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(getHeader(record, "sqs.message.id").value()).isEqualTo("test-message-id");
        assertThat(getHeader(record, "sqs.receipt.handle").value()).isEqualTo("test-receipt-handle");
        assertThat(getHeader(record, "sqs.sent.timestamp").value()).isEqualTo("1234567890");
        assertThat(getHeader(record, "sqs.approximate.receive.count").value()).isEqualTo("2");
    }

    @Test
    void shouldHandleJsonArrayAsNonObject() {
        String jsonBody = "[1, 2, 3, 4, 5]";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isEqualTo(jsonBody);
        assertThat(record.valueSchema()).isEqualTo(Schema.STRING_SCHEMA);
    }

    @Test
    void shouldThrowExceptionWhenSchemaRegistryNotConfigured() {
        Map<String, String> configWithoutRegistry = getTestConfig();
        configWithoutRegistry.remove("schema.registry.url");
        SqsSourceConnectorConfig configNoRegistry = new SqsSourceConnectorConfig(configWithoutRegistry);

        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body("{\"key\":\"value\"}")
                .build();

        assertThatThrownBy(() -> converter.convert(sqsMessage, configNoRegistry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Schema Registry URL is required");
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
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("aws.region", "us-east-1");
        props.put("schema.registry.url", "http://localhost:8081");
        props.put("schema.auto.register", "false");
        return props;
    }

    private Map<String, String> getFifoTestConfig() {
        Map<String, String> props = getTestConfig();
        props.put("sqs.fifo.queue", "true");
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue.fifo");
        return props;
    }
}
