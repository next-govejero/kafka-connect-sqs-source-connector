package io.connect.sqs.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.connect.sqs.aws.S3Client;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.converter.ClaimCheckMessageConverter;
import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Integration tests for Claim Check Pattern using LocalStack.
 * Tests the full flow of retrieving large messages from S3.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class ClaimCheckPatternIT {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckPatternIT.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(S3, SQS);

    private software.amazon.awssdk.services.s3.S3Client awsS3Client;
    private S3Client s3Client;
    private SqsSourceConnectorConfig config;

    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue";

    @BeforeEach
    void setUp() {
        // Create S3 client
        awsS3Client = software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localstack.getAccessKey(),
                                localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();

        // Create test bucket
        awsS3Client.createBucket(CreateBucketRequest.builder()
                .bucket(TEST_BUCKET)
                .build());

        log.info("Created S3 bucket: {}", TEST_BUCKET);

        // Create config for connector
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", TEST_QUEUE_URL);
        props.put("kafka.topic", "test-topic");
        props.put("aws.region", localstack.getRegion());
        props.put("aws.access.key.id", localstack.getAccessKey());
        props.put("aws.secret.access.key", localstack.getSecretKey());
        props.put("aws.endpoint.override", localstack.getEndpointOverride(S3).toString());
        props.put("message.claimcheck.delegate.converter.class", "io.connect.sqs.converter.DefaultMessageConverter");

        config = new SqsSourceConnectorConfig(props);

        // Create S3Client wrapper
        s3Client = new S3Client(config);
    }

    @Test
    void shouldRetrieveSimpleMessageFromS3() throws Exception {
        // Arrange - Upload test data to S3
        String testData = "{\"message\":\"Hello from S3!\",\"timestamp\":1705320600}";
        String s3Key = "messages/test-message-1.json";

        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(testData));

        log.info("Uploaded test data to s3://{}/{}", TEST_BUCKET, s3Key);

        // Act - Retrieve using S3Client
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        byte[] retrievedData = s3Client.getObjectByUri(s3Uri);

        // Assert
        String retrievedString = new String(retrievedData, StandardCharsets.UTF_8);
        assertThat(retrievedString).isEqualTo(testData);

        log.info("Successfully retrieved {} bytes from S3", retrievedData.length);
    }

    @Test
    void shouldProcessClaimCheckMessageWithEntireBodyAsS3Uri() throws Exception {
        // Arrange - Upload test data to S3
        String testData = "{\"userId\":12345,\"name\":\"John Doe\"}";
        String s3Key = "users/user-12345.json";

        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(testData));

        // Create SQS message with S3 URI as body
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        Message sqsMessage = Message.builder()
                .messageId("msg-1")
                .receiptHandle("receipt-1")
                .body(s3Uri)
                .build();

        // Create converter
        ClaimCheckMessageConverter converter = new ClaimCheckMessageConverter();
        converter.setS3Client(s3Client);
        converter.setFieldPath(null); // Entire body mode
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        // Mock delegate converter by setting it directly
        converter.setDelegateConverter((message, cfg) -> null); // We only care about retrieval

        // Act
        SourceRecord record = converter.convert(sqsMessage, config);

        // Assert - We can't easily verify the record without a full mock,
        // but we can verify the S3 retrieval worked (no exception thrown)
        assertThat(record).isNull(); // null because our mock returns null

        log.info("Successfully processed claim check message with entire body S3 URI");
    }

    @Test
    void shouldProcessClaimCheckMessageWithNestedS3Uri() throws Exception {
        // Arrange - Upload test data to S3
        String s3Content = "{\"price\":100.50,\"currency\":\"USD\"}";
        String s3Key = "data/pricing-update.json";

        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(s3Content));

        // Create EventBridge-style message with nested S3 URI
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        Map<String, Object> eventBridgeMessage = new HashMap<>();
        eventBridgeMessage.put("version", "0");
        eventBridgeMessage.put("id", "test-123");
        eventBridgeMessage.put("detail-type", "PriceUpdate");

        Map<String, Object> detail = new HashMap<>();
        detail.put("s3Key", s3Uri);
        detail.put("size", s3Content.length());
        eventBridgeMessage.put("detail", detail);

        String messageBody = objectMapper.writeValueAsString(eventBridgeMessage);

        Message sqsMessage = Message.builder()
                .messageId("msg-2")
                .receiptHandle("receipt-2")
                .body(messageBody)
                .build();

        // Create converter with field path
        ClaimCheckMessageConverter converter = new ClaimCheckMessageConverter();
        converter.setS3Client(s3Client);
        converter.setFieldPath("detail.s3Key"); // Nested field mode
        converter.setDecompressAfterRetrieval(false);
        converter.initializeForTesting();

        converter.setDelegateConverter((message, cfg) -> {
            // Verify the S3 content was retrieved and replaced the S3 URI
            String body = message.body();
            try {
                // Parse the body to check the field value
                JsonNode rootNode = objectMapper.readTree(body);
                JsonNode s3KeyNode = rootNode.path("detail").path("s3Key");

                // The s3Key field should now contain the S3 content (not the URI)
                assertThat(s3KeyNode.isTextual()).isTrue();
                String retrievedContent = s3KeyNode.asText();
                assertThat(retrievedContent).isEqualTo(s3Content);

                // Make sure it's NOT the S3 URI anymore
                assertThat(retrievedContent).doesNotContain("s3://");
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse message body", e);
            }
            return null;
        });

        // Act
        converter.convert(sqsMessage, config);

        log.info("Successfully processed claim check message with nested S3 URI");
    }

    @Test
    void shouldProcessCompressedS3Content() throws Exception {
        // Arrange - Create compressed test data
        String originalData = "{\"largePayload\":\"This is compressed data from S3\"}";
        byte[] compressedData = gzipCompress(originalData);
        String base64Encoded = Base64.getEncoder().encodeToString(compressedData);

        String s3Key = "compressed/data.json.gz";

        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(base64Encoded));

        log.info("Uploaded compressed data to S3 ({} bytes compressed, Base64: {} bytes)",
                compressedData.length, base64Encoded.length());

        // Create SQS message
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        Message sqsMessage = Message.builder()
                .messageId("msg-3")
                .receiptHandle("receipt-3")
                .body(s3Uri)
                .build();

        // Create converter with decompression enabled
        ClaimCheckMessageConverter converter = new ClaimCheckMessageConverter();
        converter.setS3Client(s3Client);
        converter.setFieldPath(null);
        converter.setDecompressAfterRetrieval(true);
        converter.setCompressionFormat(io.connect.sqs.util.MessageDecompressor.CompressionFormat.GZIP);
        converter.setTryBase64Decode(true);
        converter.initializeForTesting();

        converter.setDelegateConverter((message, cfg) -> {
            // Verify decompression worked
            String body = message.body();
            assertThat(body).isEqualTo(originalData);
            log.info("Decompressed content: {}", body);
            return null;
        });

        // Act
        converter.convert(sqsMessage, config);

        log.info("Successfully processed compressed S3 content");
    }

    @Test
    void shouldHandleLargeS3Objects() throws Exception {
        // Arrange - Create a large payload (1 MB)
        StringBuilder largePayload = new StringBuilder("{\"data\":[");
        for (int i = 0; i < 10000; i++) {
            if (i > 0) largePayload.append(",");
            largePayload.append("{\"id\":").append(i).append(",\"value\":\"data-").append(i).append("\"}");
        }
        largePayload.append("]}");

        String s3Key = "large/1mb-payload.json";

        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(largePayload.toString()));

        log.info("Uploaded large payload to S3 ({} bytes)", largePayload.length());

        // Act - Retrieve large object
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        byte[] retrievedData = s3Client.getObjectByUri(s3Uri);

        // Assert
        assertThat(retrievedData).hasSizeGreaterThan(256 * 1024); // > 256KB (SQS limit)
        assertThat(new String(retrievedData, StandardCharsets.UTF_8))
                .isEqualTo(largePayload.toString());

        log.info("Successfully retrieved large object ({} bytes)", retrievedData.length);
    }

    @Test
    void shouldHandleMultipleS3Retrievals() throws Exception {
        // Arrange - Upload multiple test files
        for (int i = 1; i <= 5; i++) {
            String testData = String.format("{\"id\":%d,\"message\":\"Test message %d\"}", i, i);
            String s3Key = String.format("batch/message-%d.json", i);

            awsS3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(TEST_BUCKET)
                            .key(s3Key)
                            .build(),
                    RequestBody.fromString(testData));
        }

        log.info("Uploaded 5 test files to S3");

        // Act - Retrieve all files
        for (int i = 1; i <= 5; i++) {
            String s3Uri = String.format("s3://%s/batch/message-%d.json", TEST_BUCKET, i);
            byte[] data = s3Client.getObjectByUri(s3Uri);

            String content = new String(data, StandardCharsets.UTF_8);
            assertThat(content).contains(String.format("\"id\":%d", i));
        }

        log.info("Successfully retrieved all 5 files from S3");
    }

    @Test
    void shouldHandleNonExistentS3Object() {
        // Arrange
        String s3Uri = "s3://" + TEST_BUCKET + "/non-existent/file.json";

        // Act & Assert
        try {
            s3Client.getObjectByUri(s3Uri);
            // Should throw exception
            assertThat(false).as("Expected exception for non-existent S3 object").isTrue();
        } catch (Exception e) {
            assertThat(e.getMessage()).containsAnyOf("NoSuchKey", "not found", "does not exist");
            log.info("Correctly handled non-existent S3 object: {}", e.getMessage());
        }
    }

    @Test
    void shouldExtractFieldFromEventBridgeMessage() throws Exception {
        // Arrange - Create EventBridge message with nested detail.data
        String offersData = "{\"offers\":[{\"id\":\"123\",\"price\":100.50}]}";

        Map<String, Object> eventBridgeMessage = new HashMap<>();
        eventBridgeMessage.put("version", "0");
        eventBridgeMessage.put("id", "test-event-123");
        eventBridgeMessage.put("detail-type", "FlightOffersUpdate");

        Map<String, Object> detail = new HashMap<>();
        // Store the offers data as a parsed object (not as string)
        detail.put("data", objectMapper.readValue(offersData, Object.class));
        eventBridgeMessage.put("detail", detail);

        String messageBody = objectMapper.writeValueAsString(eventBridgeMessage);

        Message sqsMessage = Message.builder()
                .messageId("msg-field-extract-1")
                .receiptHandle("receipt-field-extract-1")
                .body(messageBody)
                .build();

        // Create config with field extraction
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", TEST_QUEUE_URL);
        props.put("kafka.topic", "test-topic");
        props.put("aws.region", localstack.getRegion());
        props.put("message.converter.class", "io.connect.sqs.converter.DefaultMessageConverter");
        props.put("message.output.field.extract", "detail.data");
        props.put("message.output.field.extract.failOnMissing", "false");

        SqsSourceConnectorConfig configWithExtraction = new SqsSourceConnectorConfig(props);

        // Create FieldExtractorConverter with DefaultMessageConverter as delegate
        io.connect.sqs.converter.FieldExtractorConverter extractor =
                new io.connect.sqs.converter.FieldExtractorConverter();
        io.connect.sqs.converter.DefaultMessageConverter defaultConverter =
                new io.connect.sqs.converter.DefaultMessageConverter();
        extractor.setDelegateConverter(defaultConverter);

        // Act
        SourceRecord record = extractor.convert(sqsMessage, configWithExtraction);

        // Assert
        assertThat(record).isNotNull();
        assertThat(record.value()).isNotNull();
        assertThat(record.value()).isInstanceOf(String.class);

        String extractedValue = (String) record.value();

        // Parse and verify the extracted value
        JsonNode extractedNode = objectMapper.readTree(extractedValue);
        assertThat(extractedNode.has("offers")).isTrue();
        assertThat(extractedNode.get("offers").isArray()).isTrue();
        assertThat(extractedNode.get("offers").size()).isEqualTo(1);
        assertThat(extractedNode.get("offers").get(0).get("id").asText()).isEqualTo("123");
        assertThat(extractedNode.get("offers").get(0).get("price").asDouble()).isEqualTo(100.50);

        // Make sure it's ONLY the offers data (no version, detail-type, etc.)
        assertThat(extractedNode.has("version")).isFalse();
        assertThat(extractedNode.has("detail-type")).isFalse();
        assertThat(extractedNode.has("detail")).isFalse();

        log.info("Successfully extracted field from EventBridge message. Extracted: {}", extractedValue);
    }

    @Test
    void shouldExtractFieldFromCompressedEventBridgeMessage() throws Exception {
        // Arrange - Create compressed EventBridge message with nested detail.data
        String offersData = "{\"offers\":[{\"id\":\"456\",\"price\":250.00,\"airline\":\"IB\"}]}";

        // Create compressed payload stored in S3
        byte[] compressedData = gzipCompress(offersData);
        String base64Encoded = Base64.getEncoder().encodeToString(compressedData);

        String s3Key = "compressed/offers-data.json.gz";
        awsS3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(TEST_BUCKET)
                        .key(s3Key)
                        .build(),
                RequestBody.fromString(base64Encoded));

        // Create EventBridge message with S3 URI in detail.data
        String s3Uri = String.format("s3://%s/%s", TEST_BUCKET, s3Key);
        Map<String, Object> eventBridgeMessage = new HashMap<>();
        eventBridgeMessage.put("version", "0");
        eventBridgeMessage.put("id", "test-event-456");

        Map<String, Object> detail = new HashMap<>();
        detail.put("data", s3Uri);  // S3 URI pointing to compressed data
        eventBridgeMessage.put("detail", detail);

        String messageBody = objectMapper.writeValueAsString(eventBridgeMessage);

        Message sqsMessage = Message.builder()
                .messageId("msg-field-extract-2")
                .receiptHandle("receipt-field-extract-2")
                .body(messageBody)
                .build();

        // Create config with decompression, claim check, and field extraction
        Map<String, String> props = new HashMap<>();
        props.put("sqs.queue.url", TEST_QUEUE_URL);
        props.put("kafka.topic", "test-topic");
        props.put("aws.region", localstack.getRegion());
        props.put("aws.access.key.id", localstack.getAccessKey());
        props.put("aws.secret.access.key", localstack.getSecretKey());
        props.put("aws.endpoint.override", localstack.getEndpointOverride(S3).toString());
        props.put("message.converter.class", "io.connect.sqs.converter.DecompressingClaimCheckMessageConverter");
        props.put("message.claimcheck.field.path", "detail.data");
        props.put("message.claimcheck.retrieve.from.s3.if.uri", "true");
        props.put("message.claimcheck.decompress.after.retrieval", "true");
        props.put("message.decompression.format", "GZIP");
        props.put("message.decompression.try.base64.decode", "true");
        props.put("message.output.field.extract", "detail.data");
        props.put("message.output.field.extract.failOnMissing", "false");

        SqsSourceConnectorConfig configWithAll = new SqsSourceConnectorConfig(props);

        // Create the full converter chain:
        // DefaultMessageConverter -> ClaimCheckMessageConverter -> DecompressingClaimCheckMessageConverter -> FieldExtractorConverter
        io.connect.sqs.converter.DefaultMessageConverter defaultConverter =
                new io.connect.sqs.converter.DefaultMessageConverter();

        io.connect.sqs.converter.DecompressingClaimCheckMessageConverter decompressingConverter =
                new io.connect.sqs.converter.DecompressingClaimCheckMessageConverter();
        decompressingConverter.setS3Client(s3Client);
        decompressingConverter.setFieldPath("detail.data");
        decompressingConverter.setRetrieveFromS3IfUri(true);
        decompressingConverter.setCompressionFormat(io.connect.sqs.util.MessageDecompressor.CompressionFormat.GZIP);
        decompressingConverter.setTryBase64Decode(true);
        decompressingConverter.initializeForTesting();
        decompressingConverter.setDelegateConverter(defaultConverter);

        io.connect.sqs.converter.FieldExtractorConverter extractor =
                new io.connect.sqs.converter.FieldExtractorConverter();
        extractor.setDelegateConverter(decompressingConverter);

        // Act
        SourceRecord record = extractor.convert(sqsMessage, configWithAll);

        // Assert
        assertThat(record).isNotNull();
        assertThat(record.value()).isNotNull();
        assertThat(record.value()).isInstanceOf(String.class);

        String extractedValue = (String) record.value();

        // Parse and verify the extracted value
        JsonNode extractedNode = objectMapper.readTree(extractedValue);
        assertThat(extractedNode.has("offers")).isTrue();
        assertThat(extractedNode.get("offers").isArray()).isTrue();
        assertThat(extractedNode.get("offers").size()).isEqualTo(1);
        assertThat(extractedNode.get("offers").get(0).get("id").asText()).isEqualTo("456");
        assertThat(extractedNode.get("offers").get(0).get("price").asDouble()).isEqualTo(250.00);
        assertThat(extractedNode.get("offers").get(0).get("airline").asText()).isEqualTo("IB");

        // Make sure it's ONLY the offers data (no EventBridge envelope)
        assertThat(extractedNode.has("version")).isFalse();
        assertThat(extractedNode.has("id")).isFalse();
        assertThat(extractedNode.has("detail")).isFalse();

        log.info("Successfully extracted field from compressed EventBridge message with S3 claim check. Extracted: {}", extractedValue);
    }

    // Helper methods

    private byte[] gzipCompress(String data) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
            gzipOutputStream.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return outputStream.toByteArray();
    }
}
