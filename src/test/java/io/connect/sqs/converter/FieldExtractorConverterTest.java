package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FieldExtractorConverterTest {

    private FieldExtractorConverter converter;
    private MessageConverter mockDelegateConverter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new FieldExtractorConverter();
        mockDelegateConverter = mock(MessageConverter.class);
    }

    @Test
    void testExtractNestedField() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"detail\":{\"data\":{\"offers\":[{\"id\":\"123\"}]}}}";
        String expectedExtracted = "{\"offers\":[{\"id\":\"123\"}]}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("detail.data", false);

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(expectedExtracted);
        assertThat(result.topic()).isEqualTo("test-topic");
    }

    @Test
    void testExtractTopLevelField() {
        // Arrange
        String messageBody = "{\"data\":{\"key\":\"value\"},\"metadata\":\"extra\"}";
        String expectedExtracted = "{\"key\":\"value\"}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("data", false);

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(expectedExtracted);
    }

    @Test
    void testExtractDeeplyNestedField() {
        // Arrange
        String messageBody = "{\"a\":{\"b\":{\"c\":{\"d\":\"final-value\"}}}}";
        String expectedExtracted = "final-value";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("a.b.c.d", false);

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(expectedExtracted);
    }

    @Test
    void testFieldNotFound_FailOnMissingFalse_ReturnsOriginal() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"detail\":{\"other\":\"data\"}}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("detail.data", false);  // failOnMissing = false

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(messageBody);  // Returns original
    }

    @Test
    void testFieldNotFound_FailOnMissingTrue_ThrowsException() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"detail\":{\"other\":\"data\"}}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("detail.data", true);  // failOnMissing = true

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act & Assert
        assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("Field path 'detail.data' not found");
    }

    @Test
    void testNoFieldPathConfigured_ReturnsOriginal() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"detail\":{\"data\":\"value\"}}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig(null, false);  // No field path

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(messageBody);  // Returns original
    }

    @Test
    void testEmptyFieldPath_ReturnsOriginal() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"detail\":{\"data\":\"value\"}}";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("", false);  // Empty field path

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(messageBody);  // Returns original
    }

    @Test
    void testInvalidJson_FailOnMissingFalse_ReturnsOriginal() {
        // Arrange
        String messageBody = "not-valid-json";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("detail.data", false);

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualTo(messageBody);  // Returns original
    }

    @Test
    void testInvalidJson_FailOnMissingTrue_ThrowsException() {
        // Arrange
        String messageBody = "not-valid-json";

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig("detail.data", true);

        SourceRecord delegateRecord = createSourceRecord("test-topic", messageBody);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act & Assert
        assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(ConnectException.class);
    }

    @Test
    void testNullValue_ReturnsOriginal() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", "body");
        config = createConfig("detail.data", false);

        SourceRecord delegateRecord = new SourceRecord(
                null, null, "test-topic", null, null, null, null, null
        );
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(delegateRecord);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNotNull();
        assertThat(result.value()).isNull();
    }

    @Test
    void testDelegateReturnsNull_ReturnsNull() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", "body");
        config = createConfig("detail.data", false);

        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(null);

        converter.setDelegateConverter(mockDelegateConverter);

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isNull();
    }

    // Helper methods

    private Message createMessage(String messageId, String body) {
        return Message.builder()
                .messageId(messageId)
                .body(body)
                .receiptHandle("receipt-" + messageId)
                .build();
    }

    private SqsSourceConnectorConfig createConfig(String fieldPath, boolean failOnMissing) {
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("aws.region", "us-east-1");
        props.put("message.converter.class", "io.connect.sqs.converter.DefaultMessageConverter");

        if (fieldPath != null) {
            props.put("message.output.field.extract", fieldPath);
        }
        props.put("message.output.field.extract.failOnMissing", String.valueOf(failOnMissing));

        return new SqsSourceConnectorConfig(props);
    }

    private SourceRecord createSourceRecord(String topic, String value) {
        return new SourceRecord(
                null,
                null,
                topic,
                null,
                Schema.STRING_SCHEMA,
                "test-key",
                Schema.STRING_SCHEMA,
                value
        );
    }
}
