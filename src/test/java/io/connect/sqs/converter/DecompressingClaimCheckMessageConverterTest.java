package io.connect.sqs.converter;

import io.connect.sqs.aws.S3Client;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.MessageDecompressor;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class DecompressingClaimCheckMessageConverterTest {

    private DecompressingClaimCheckMessageConverter converter;
    private MessageConverter mockDelegateConverter;
    private S3Client mockS3Client;
    private SqsSourceConnectorConfig config;

    @BeforeEach
    void setUp() {
        converter = new DecompressingClaimCheckMessageConverter();
        mockDelegateConverter = mock(MessageConverter.class);
        mockS3Client = mock(S3Client.class);
    }

    @Test
    void testDecompressFieldWithDirectJson() throws IOException {
        // Arrange - Compressed JSON data (not S3 URI)
        String originalData = "{\"price\":100,\"currency\":\"USD\"}";
        byte[] gzippedData = gzipCompress(originalData);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String messageBody = String.format(
                "{\"version\":\"0\",\"detail-type\":\"PriceCache.Updated\",\"detail\":{\"data\":\"%s\"}}",
                base64Encoded);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.setRetrieveFromS3IfUri(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressFieldWithS3Uri() throws IOException {
        // Arrange - Compressed S3 URI
        String s3Uri = "s3://my-bucket/large-data.json";
        byte[] gzippedS3Uri = gzipCompress(s3Uri);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedS3Uri);

        String messageBody = String.format(
                "{\"version\":\"0\",\"detail-type\":\"PriceCache.Updated\",\"detail\":{\"data\":\"%s\"}}",
                base64Encoded);

        // Mock S3 content
        String s3Content = "{\"price\":200,\"currency\":\"EUR\"}";
        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenReturn(s3Content.getBytes(StandardCharsets.UTF_8));

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.setRetrieveFromS3IfUri(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockS3Client).getObjectByUri(eq(s3Uri));
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDecompressWithNonS3Content() throws IOException {
        // Arrange - Regular JSON, not an S3 URI
        String regularJson = "{\"message\":\"regular content\"}";
        byte[] gzippedData = gzipCompress(regularJson);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String messageBody = String.format(
                "{\"detail\":{\"data\":\"%s\"}}",
                base64Encoded);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.setRetrieveFromS3IfUri(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should not call S3 client
        assertThat(result).isEqualTo(expectedRecord);
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
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original message when field not found
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testEmptyFieldPath() {
        // Arrange
        String messageBody = "{\"data\":\"something\"}";
        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath(""); // Empty field path
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should return original when field path is empty
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testDisableS3Retrieval() throws IOException {
        // Arrange - Compressed S3 URI, but S3 retrieval disabled
        String s3Uri = "s3://my-bucket/data.json";
        byte[] gzippedS3Uri = gzipCompress(s3Uri);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedS3Uri);

        String messageBody = String.format(
                "{\"detail\":{\"data\":\"%s\"}}",
                base64Encoded);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();
        converter.setRetrieveFromS3IfUri(false); // Disabled - must be after initializeForTesting

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert - should NOT retrieve from S3
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testNestedFieldPath() throws IOException {
        // Arrange - Deeply nested field
        String originalData = "{\"nested\":\"value\"}";
        byte[] gzippedData = gzipCompress(originalData);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedData);

        String messageBody = String.format(
                "{\"level1\":{\"level2\":{\"level3\":{\"data\":\"%s\"}}}}",
                base64Encoded);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        SourceRecord expectedRecord = mock(SourceRecord.class);
        when(mockDelegateConverter.convert(any(Message.class), any(SqsSourceConnectorConfig.class)))
                .thenReturn(expectedRecord);

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("level1.level2.level3.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act
        SourceRecord result = converter.convert(sqsMessage, config);

        // Assert
        assertThat(result).isEqualTo(expectedRecord);
        verify(mockDelegateConverter).convert(any(Message.class), any(SqsSourceConnectorConfig.class));
    }

    @Test
    void testS3AccessFailure() throws IOException {
        // Arrange - Compressed S3 URI
        String s3Uri = "s3://my-bucket/missing-data.json";
        byte[] gzippedS3Uri = gzipCompress(s3Uri);
        String base64Encoded = Base64.getEncoder().encodeToString(gzippedS3Uri);

        String messageBody = String.format(
                "{\"detail\":{\"data\":\"%s\"}}",
                base64Encoded);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        // Mock S3 failure
        when(mockS3Client.getObjectByUri(eq(s3Uri)))
                .thenThrow(new IOException("S3 access denied or network error"));

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.setRetrieveFromS3IfUri(true);
        converter.initializeForTesting();

        // Act & Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(org.apache.kafka.connect.errors.ConnectException.class)
                .hasMessageContaining("Decompressing claim check processing failed")
                .hasRootCauseInstanceOf(IOException.class);

        verify(mockS3Client).getObjectByUri(eq(s3Uri));
    }

    @Test
    void testMalformedBase64() {
        // Arrange - Invalid Base64
        String invalidBase64 = "NotValidBase64!!";

        String messageBody = String.format(
                "{\"detail\":{\"data\":\"%s\"}}",
                invalidBase64);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act & Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(org.apache.kafka.connect.errors.ConnectException.class)
                .hasMessageContaining("Decompressing claim check processing failed");
    }

    @Test
    void testMalformedGzip() {
        // Arrange - Valid Base64 but invalid GZIP content
        String validBase64InvalidGzip = Base64.getEncoder()
                .encodeToString("NotGzippedContent".getBytes(StandardCharsets.UTF_8));

        String messageBody = String.format(
                "{\"detail\":{\"data\":\"%s\"}}",
                validBase64InvalidGzip);

        Message sqsMessage = createMessage("msg-1", messageBody);
        config = createConfig();

        converter.setDelegateConverter(mockDelegateConverter);
        converter.setS3Client(mockS3Client);
        converter.setFieldPath("detail.data");
        converter.setCompressionFormat(MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        // Act & Assert
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> converter.convert(sqsMessage, config))
                .isInstanceOf(org.apache.kafka.connect.errors.ConnectException.class)
                .hasMessageContaining("Decompressing claim check processing failed");
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
        props.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
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
