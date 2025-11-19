package io.connect.sqs.converter;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.MessageDecompressor;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DecompressingMessageConverterTest {

    private DecompressingMessageConverter converter;
    private MessageConverter mockDelegateConverter;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new DecompressingMessageConverter();
        mockDelegateConverter = mock(MessageConverter.class);
    }

    @Test
    void testDecompressEntireBodyGzip() throws IOException {
        // Arrange
        String originalData = "{\"message\":\"Hello, World!\"}";
        byte[] gzippedData = gzipCompress(originalData);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        Message sqsMessage = createMessage("msg-1", base64Encoded);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath(null); // Explicitly set to null for entire body decompression
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);

        // Verify delegate was called with decompressed message
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressFieldPath() throws IOException {
        // Arrange - EventBridge event with compressed detail.data field
        String compressedValue = "{\"price\":100,\"currency\":\"USD\"}";
        byte[] gzippedData = gzipCompress(compressedValue);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String messageBody = String.format(
            "{\"version\":\"0\",\"id\":\"test-123\",\"detail-type\":\"PriceCache.Updated\",\"detail\":{\"data\":\"%s\"}}",
            base64Encoded
        );

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath("detail.data");
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressFieldPathNotFound() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"id\":\"test-123\"}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath("detail.data");
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original message when field not found
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressFieldPathNonString() {
        // Arrange - field value is not a string
        String messageBody = "{\"version\":\"0\",\"id\":\"test-123\",\"detail\":{\"data\":123}}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath("detail.data");
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original when field is not a string
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressWithGzipFormat() throws IOException {
        // Arrange
        String originalData = "{\"test\":\"data\"}";
        byte[] gzippedData = gzipCompress(originalData);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        Message sqsMessage = createMessage("msg-1", base64Encoded);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressWithoutBase64Decoding() {
        // Arrange
        String plainData = "{\"test\":\"data\"}";
        Message sqsMessage = createMessage("msg-1", plainData);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath(null); // Entire body decompression
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressNullMessage() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", null);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath(null); // Entire body decompression
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressEmptyMessage() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", "");
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath(null); // Entire body decompression
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testInitializeFromConfig() {
        // Arrange
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("message.decompression.enabled", "true");
        props.put("message.decompression.delegate.converter.class", "io.connect.sqs.converter.DefaultMessageConverter");
        props.put("message.decompression.field.path", "detail.data");
        props.put("message.decompression.format", "AUTO");
        props.put("message.decompression.base64.decode", "true");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        // Use valid JSON with the configured field path
        String messageBody = "{\"version\":\"0\",\"detail\":{\"data\":\"test-value\"}}";
        Message sqsMessage = createMessage("msg-1", messageBody);

        DecompressingMessageConverter converter = new DecompressingMessageConverter();

        // Act
        converter.convert(sqsMessage, config);

        // Assert - no exception means initialization succeeded
    }

    @Test
    void testInitializeWithInvalidDelegateClass() {
        // Arrange
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("message.decompression.enabled", "true");
        props.put("message.decompression.delegate.converter.class", "com.invalid.NonExistentConverter");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);
        Message sqsMessage = createMessage("msg-1", "test");

        DecompressingMessageConverter converter = new DecompressingMessageConverter();

        // Act & Assert
        assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("Failed to instantiate delegate converter");
    }

    @Test
    void testDecompressNestedFieldPath() throws IOException {
        // Arrange - deeply nested field
        String compressedValue = "nested-value";
        byte[] gzippedData = gzipCompress(compressedValue);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String messageBody = String.format(
            "{\"level1\":{\"level2\":{\"level3\":{\"data\":\"%s\"}}}}",
            base64Encoded
        );

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setFieldPath("level1.level2.level3.data");
        converter.setFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    // Helper methods

    private Message createMessage(String messageId, String body) {
        return Message.builder()
                .messageId(messageId)
                .receiptHandle("receipt-" + messageId)
                .body(body)
                .build();
    }

    private SqsSourceConnectorConfig createConfig() {
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        return new SqsSourceConnectorConfig(props);
    }

    private byte[] gzipCompress(String data) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }
}
