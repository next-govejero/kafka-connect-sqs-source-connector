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

class JsonSchemaMessageConverterTest {

    private JsonSchemaMessageConverter converter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new JsonSchemaMessageConverter();
        config = new SqsSourceConnectorConfig(getTestConfig());
    }

    @Test
    void shouldConvertSimpleJsonWithSchema() {
        String jsonBody = "{\"name\":\"Alice\",\"age\":28,\"verified\":true}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .md5OfBody("def456")
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.topic()).isEqualTo("test-topic");
        assertThat(record.key()).isEqualTo("test-message-id");
        assertThat(record.keySchema()).isEqualTo(Schema.STRING_SCHEMA);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("name")).isEqualTo("Alice");
        assertThat(value.get("age")).isEqualTo(28L); // JSON Schema integers map to INT64
        assertThat(value.get("verified")).isEqualTo(true);
    }

    @Test
    void shouldConvertJsonWithNestedObject() {
        String jsonBody = "{\"order\":{\"id\":\"ORD-123\",\"total\":149.99},\"shipped\":false}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("shipped")).isEqualTo(false);

        Object orderObj = value.get("order");
        assertThat(orderObj).isInstanceOf(Struct.class);
        Struct order = (Struct) orderObj;
        assertThat(order.get("id")).isEqualTo("ORD-123");
        assertThat(order.get("total")).isEqualTo(149.99);
    }

    @Test
    void shouldConvertJsonWithArray() {
        String jsonBody = "{\"products\":[\"laptop\",\"mouse\",\"keyboard\"],\"quantity\":3}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();

        Object productsObj = value.get("products");
        assertThat(productsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> products = (List<String>) productsObj;
        assertThat(products).containsExactly("laptop", "mouse", "keyboard");
        assertThat(value.get("quantity")).isEqualTo(3L);
    }

    @Test
    void shouldConvertNonJsonMessageAsString() {
        String plainText = "Plain text message without JSON";
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
        String jsonBody = "{\"event\":\"test\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        Header schemaTypeHeader = getHeader(record, "sqs.schema.type");
        assertThat(schemaTypeHeader).isNotNull();
        assertThat(schemaTypeHeader.value()).isEqualTo("JSON Schema");
    }

    @Test
    void shouldUseFifoMessageGroupIdAsKey() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "event-group-789");
        attributes.put("MessageDeduplicationId", "dedup-001");

        String jsonBody = "{\"eventType\":\"click\",\"timestamp\":1234567890}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .attributesWithStrings(attributes)
                .build();

        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(getFifoTestConfig());
        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        assertThat(record.key()).isEqualTo("event-group-789");
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
        Struct value = (Struct) record.value();
        assertThat(value.schema().fields()).isEmpty();
    }

    @Test
    void shouldConvertJsonWithNullValues() {
        String jsonBody = "{\"firstName\":\"Bob\",\"middleName\":null,\"lastName\":\"Smith\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("firstName")).isEqualTo("Bob");
        assertThat(value.get("middleName")).isNull();
        assertThat(value.get("lastName")).isEqualTo("Smith");
    }

    @Test
    void shouldConvertJsonWithNumbers() {
        String jsonBody = "{\"integer\":100,\"decimal\":3.14159,\"negative\":-42}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("integer")).isEqualTo(100L);
        assertThat(value.get("decimal")).isEqualTo(3.14159);
        assertThat(value.get("negative")).isEqualTo(-42L);
    }

    @Test
    void shouldIncludeSourcePartitionAndOffset() {
        String jsonBody = "{\"payload\":\"data\"}";
        Message sqsMessage = Message.builder()
                .messageId("json-schema-msg-456")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.sourcePartition()).containsKey("queue_url");
        assertThat(record.sourcePartition().get("queue_url")).isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        assertThat(record.sourceOffset()).containsKey("message_id");
        assertThat(record.sourceOffset().get("message_id")).isEqualTo("json-schema-msg-456");
    }

    @Test
    void shouldIncludeSqsMetadataHeaders() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("SentTimestamp", "9876543210");
        attributes.put("ApproximateReceiveCount", "1");
        attributes.put("ApproximateFirstReceiveTimestamp", "9876543220");

        String jsonBody = "{\"message\":\"test\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .receiptHandle("receipt-handle-xyz")
                .md5OfBody("md5hash123")
                .attributesWithStrings(attributes)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(getHeader(record, "sqs.message.id").value()).isEqualTo("test-message-id");
        assertThat(getHeader(record, "sqs.receipt.handle").value()).isEqualTo("receipt-handle-xyz");
        assertThat(getHeader(record, "sqs.md5.of.body").value()).isEqualTo("md5hash123");
        assertThat(getHeader(record, "sqs.sent.timestamp").value()).isEqualTo("9876543210");
        assertThat(getHeader(record, "sqs.approximate.receive.count").value()).isEqualTo("1");
    }

    @Test
    void shouldHandleJsonArrayAsNonObject() {
        String jsonBody = "[\"element1\", \"element2\", \"element3\"]";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isEqualTo(jsonBody);
        assertThat(record.valueSchema()).isEqualTo(Schema.STRING_SCHEMA);
    }

    @Test
    void shouldHandleComplexNestedStructure() {
        String jsonBody = "{\"user\":{\"profile\":{\"name\":\"Test\",\"email\":\"test@test.com\"}},\"active\":true}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("active")).isEqualTo(true);

        Struct user = (Struct) value.get("user");
        assertThat(user).isNotNull();
        Struct profile = (Struct) user.get("profile");
        assertThat(profile.get("name")).isEqualTo("Test");
        assertThat(profile.get("email")).isEqualTo("test@test.com");
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
