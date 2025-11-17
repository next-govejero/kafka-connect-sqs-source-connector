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

class AvroMessageConverterTest {

    private AvroMessageConverter converter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new AvroMessageConverter();
        config = new SqsSourceConnectorConfig(getTestConfig());
    }

    @Test
    void shouldConvertSimpleJsonToAvro() {
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
    void shouldConvertJsonWithNestedObject() {
        String jsonBody = "{\"user\":{\"id\":1,\"email\":\"test@test.com\"},\"status\":\"active\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("status")).isEqualTo("active");

        Object userObj = value.get("user");
        assertThat(userObj).isInstanceOf(Struct.class);
        Struct user = (Struct) userObj;
        assertThat(user.get("id")).isEqualTo(1);
        assertThat(user.get("email")).isEqualTo("test@test.com");
    }

    @Test
    void shouldConvertJsonWithArray() {
        String jsonBody = "{\"items\":[\"apple\",\"banana\",\"orange\"],\"count\":3}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();

        Object itemsObj = value.get("items");
        assertThat(itemsObj).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) itemsObj;
        assertThat(items).containsExactly("apple", "banana", "orange");
        assertThat(value.get("count")).isEqualTo(3);
    }

    @Test
    void shouldConvertNonJsonMessageAsString() {
        String plainText = "This is not JSON";
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
        assertThat(schemaTypeHeader.value()).isEqualTo("Avro");
    }

    @Test
    void shouldUseFifoMessageGroupIdAsKey() {
        Map<String, String> attributes = new HashMap<>();
        attributes.put("MessageGroupId", "order-123");
        attributes.put("MessageDeduplicationId", "dedup-abc");

        String jsonBody = "{\"orderId\":\"123\",\"amount\":99.99}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .attributesWithStrings(attributes)
                .build();

        SqsSourceConnectorConfig fifoConfig = new SqsSourceConnectorConfig(getFifoTestConfig());
        SourceRecord record = converter.convert(sqsMessage, fifoConfig);

        assertThat(record.key()).isEqualTo("order-123");
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
        String jsonBody = "{\"name\":\"John\",\"nickname\":null,\"age\":25}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("name")).isEqualTo("John");
        assertThat(value.get("nickname")).isNull();
        assertThat(value.get("age")).isEqualTo(25);
    }

    @Test
    void shouldConvertJsonWithDifferentNumericTypes() {
        String jsonBody = "{\"intValue\":42,\"longValue\":9999999999,\"doubleValue\":3.14159}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.value()).isInstanceOf(Struct.class);
        Struct value = (Struct) record.value();
        assertThat(value.get("intValue")).isEqualTo(42);
        assertThat(value.get("longValue")).isEqualTo(9999999999L);
        assertThat(value.get("doubleValue")).isEqualTo(3.14159);
    }

    @Test
    void shouldIncludeSourcePartitionAndOffset() {
        String jsonBody = "{\"key\":\"value\"}";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        SourceRecord record = converter.convert(sqsMessage, config);

        assertThat(record.sourcePartition()).containsKey("queue_url");
        assertThat(record.sourcePartition().get("queue_url")).isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        assertThat(record.sourceOffset()).containsKey("message_id");
        assertThat(record.sourceOffset().get("message_id")).isEqualTo("test-message-id");
    }

    @Test
    void shouldHandleJsonArray() {
        String jsonBody = "[1, 2, 3]";
        Message sqsMessage = Message.builder()
                .messageId("test-message-id")
                .body(jsonBody)
                .build();

        // Non-object JSON should be converted to string
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
