package io.connect.sqs.converter;

import io.connect.sqs.aws.S3Client;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ClaimCheckMessageConverterTest {

    private ClaimCheckMessageConverter converter;
    private MessageConverter mockDelegateConverter;
    private S3Client mockS3Client;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new ClaimCheckMessageConverter();
        mockDelegateConverter = mock(MessageConverter.class);
        mockS3Client = mock(S3Client.class);
    }

    @Test
    void testRetrieveEntireBodyFromS3() throws IOException {
        // Arrange
        String s3Uri = "s3://my-bucket/large-message.json";
        String s3Content = "{\"message\":\"This is a large message from S3\"}";

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(s3Content.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", s3Uri);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null); // Entire body is S3 URI
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testRetrieveFromFieldPath() throws IOException {
        // Arrange - EventBridge event with S3 URI in detail.s3Key field
        String s3Uri = "s3://my-bucket/large-data.json";
        String s3Content = "{\"price\":100,\"currency\":\"USD\"}";

        String messageBody = String.format(
            "{\"version\":\"0\",\"id\":\"test-123\",\"detail-type\":\"PriceCache.Updated\",\"detail\":{\"s3Key\":\"%s\"}}",
            s3Uri
        );

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(s3Content.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.s3Key");
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testRetrieveAndDecompressFromS3() throws IOException {
        // Arrange
        String s3Uri = "s3://my-bucket/compressed-message.json.gz";
        String originalData = "{\"message\":\"Compressed data from S3\"}";
        byte[] compressedData = gzipCompress(originalData);
        String base64Compressed = Base64.getEncoder().encodeToString(compressedData);

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(base64Compressed.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", s3Uri);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null);
        converter.setDecompressAfterRetrieval(true);
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.AUTO);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testRetrieveWithNestedFieldPath() throws IOException {
        // Arrange - deeply nested S3 URI
        String s3Uri = "s3://my-bucket/nested-data.json";
        String s3Content = "nested-value";

        String messageBody = String.format(
            "{\"level1\":{\"level2\":{\"level3\":{\"s3Ref\":\"%s\"}}}}",
            s3Uri
        );

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(s3Content.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("level1.level2.level3.s3Ref");
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testFieldPathNotFound() {
        // Arrange
        String messageBody = "{\"version\":\"0\",\"id\":\"test-123\"}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.s3Key");
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original message when field not found
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testFieldPathNonString() {
        // Arrange - field value is not a string
        String messageBody = "{\"version\":\"0\",\"id\":\"test-123\",\"detail\":{\"s3Key\":123}}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.s3Key");
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original when field is not a string
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testNonS3UriInBody() {
        // Arrange - body doesn't contain S3 URI
        String messageBody = "{\"message\":\"Regular message without S3 reference\"}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null); // Entire body
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original message as-is
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testNullMessage() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", null);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null);
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testEmptyMessage() {
        // Arrange
        Message sqsMessage = createMessage("msg-1", "");
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null);
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testS3RetrievalFailure() throws IOException {
        // Arrange
        String s3Uri = "s3://my-bucket/non-existent.json";

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenThrow(new IOException("S3 object not found"));

        Message sqsMessage = createMessage("msg-1", s3Uri);
        config = createConfig();

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(null);
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act & Assert
        assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("Claim check S3 retrieval failed");
    }

    @Test
    void testInitializeFromConfig() throws IOException {
        // Arrange
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("message.claimcheck.enabled", "true");
        props.put("message.claimcheck.delegate.converter.class", "io.connect.sqs.converter.DefaultMessageConverter");
        props.put("message.claimcheck.field.path", "detail.s3Key");
        props.put("message.claimcheck.decompress.enabled", "false");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        String s3Uri = "s3://my-bucket/test.json";
        String messageBody = String.format("{\"detail\":{\"s3Key\":\"%s\"}}", s3Uri);
        Message sqsMessage = createMessage("msg-1", messageBody);

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn("test-content".getBytes(StandardCharsets.UTF_8));

        ClaimCheckMessageConverter converter = new ClaimCheckMessageConverter();
        converter.setS3Client(mockS3Client);

        // Act
        converter.convert(sqsMessage, config);

        // Assert - no exception means initialization succeeded
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
    }

    @Test
    void testInitializeWithInvalidDelegateClass() {
        // Arrange
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789/test-queue");
        props.put("kafka.topic", "test-topic");
        props.put("message.claimcheck.enabled", "true");
        props.put("message.claimcheck.delegate.converter.class", "com.invalid.NonExistentConverter");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);
        Message sqsMessage = createMessage("msg-1", "s3://bucket/key");

        ClaimCheckMessageConverter converter = new ClaimCheckMessageConverter();
        converter.setS3Client(mockS3Client);

        // Act & Assert
        assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(ConnectException.class)
                .hasMessageContaining("Failed to instantiate delegate converter");
    }

    @Test
    void testMixedModeWithS3AndRegularMessages() throws IOException {
        // Arrange - message with both S3 URI and regular content
        String s3Uri = "s3://my-bucket/data.json";
        String s3Content = "{\"large\":\"data\"}";

        String messageBody = String.format(
            "{\"id\":\"test-123\",\"s3Data\":\"%s\",\"regularData\":\"small-content\"}",
            s3Uri
        );

        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(s3Content.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("s3Data");
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
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
