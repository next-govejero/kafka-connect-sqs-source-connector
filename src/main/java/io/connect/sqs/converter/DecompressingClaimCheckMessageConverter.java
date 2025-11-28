package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.connect.sqs.aws.S3Client;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.MessageDecompressor;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Combined message converter that:
 * 1. Decompresses data at a field path (e.g., detail.data)
 * 2. Checks if decompressed content is an S3 URI
 * 3. Retrieves from S3 if it's an S3 URI
 * 4. Delegates to another converter for final processing
 *
 * This is useful for EventBridge messages where detail.data contains:
 * - GZIP + Base64 encoded JSON (direct data)
 * - GZIP + Base64 encoded S3 URI (claim check reference)
 *
 * Usage:
 * Set
 * message.converter.class=io.connect.sqs.converter.DecompressingClaimCheckMessageConverter
 * Set message.decompression.field.path=detail.data
 * Set
 * message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
 */
public class DecompressingClaimCheckMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DecompressingClaimCheckMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MessageConverter delegateConverter;
    private S3Client s3Client;
    private String fieldPath;
    private MessageDecompressor.CompressionFormat compressionFormat;
    private boolean tryBase64Decode;
    private boolean retrieveFromS3IfUri;

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        if (delegateConverter == null) {
            initialize(config);
        }

        try {
            // Step 1: Decompress the field
            String processedBody = decompressAndMaybeRetrieveFromS3(message.body(), config);

            // Create a new message with processed body
            Message processedMessage = Message.builder()
                    .messageId(message.messageId())
                    .receiptHandle(message.receiptHandle())
                    .body(processedBody)
                    .attributes(message.attributes())
                    .messageAttributes(message.messageAttributes())
                    .md5OfBody(message.md5OfBody())
                    .build();

            // Delegate to the wrapped converter
            return delegateConverter.convert(processedMessage, config);

        } catch (Exception e) {
            log.error("Failed to process message {}: {}", message.messageId(), e.getMessage(), e);
            throw new ConnectException("Decompressing claim check processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize the delegate converter and settings from config.
     */
    private void initialize(SqsSourceConnectorConfig config) {
        // Initialize S3 client only if not already set (for testing)
        if (this.s3Client == null) {
            this.s3Client = new S3Client(config);
        }

        // Get configuration values
        String delegateClass = config.getMessageDecompressionDelegateConverterClass();
        this.fieldPath = config.getMessageDecompressionFieldPath();

        String formatStr = config.getMessageDecompressionFormat();
        this.compressionFormat = MessageDecompressor.CompressionFormat.valueOf(formatStr.toUpperCase());
        this.tryBase64Decode = config.isMessageDecompressionBase64DecodeEnabled();

        // Always try to retrieve from S3 if decompressed content is an S3 URI
        this.retrieveFromS3IfUri = true;

        // Create delegate converter instance
        try {
            Class<?> converterClass = Class.forName(delegateClass);
            this.delegateConverter = (MessageConverter) converterClass.getDeclaredConstructor().newInstance();
            log.info(
                    "Initialized DecompressingClaimCheckMessageConverter with delegate: {}, fieldPath: {}, format: {}, S3 retrieval: {}",
                    delegateClass, fieldPath, compressionFormat, retrieveFromS3IfUri);
        } catch (Exception e) {
            throw new ConnectException("Failed to instantiate delegate converter: " + delegateClass, e);
        }
    }

    /**
     * Decompresses data at field path, then checks if it's an S3 URI and retrieves
     * if needed.
     */
    private String decompressAndMaybeRetrieveFromS3(String messageBody, SqsSourceConnectorConfig config)
            throws IOException {
        if (messageBody == null || messageBody.isEmpty()) {
            return messageBody;
        }

        if (fieldPath == null || fieldPath.trim().isEmpty()) {
            log.warn("No field path specified, returning original message");
            return messageBody;
        }

        try {
            // Parse JSON
            JsonNode rootNode = objectMapper.readTree(messageBody);

            // Navigate to the field
            List<String> pathParts = Arrays.asList(fieldPath.split("\\."));
            JsonNode currentNode = rootNode;
            JsonNode parentNode = null;
            String lastKey = null;

            for (int i = 0; i < pathParts.size(); i++) {
                String key = pathParts.get(i);
                if (currentNode == null || !currentNode.has(key)) {
                    log.warn("Field path '{}' not found in message, returning original", fieldPath);
                    return messageBody;
                }

                if (i == pathParts.size() - 1) {
                    // This is the last key - the field we want to process
                    parentNode = currentNode;
                    lastKey = key;
                    currentNode = currentNode.get(key);
                } else {
                    currentNode = currentNode.get(key);
                }
            }

            if (currentNode == null || !currentNode.isTextual()) {
                log.warn("Field at path '{}' is not a string, cannot process", fieldPath);
                return messageBody;
            }

            // Step 1: Check if the field value is an S3 URI directly
            String currentValue = currentNode.asText();
            boolean retrievedFromS3 = false;

            if (retrieveFromS3IfUri && isS3Uri(currentValue)) {
                log.info("Field value is an S3 URI: {}", currentValue);
                currentValue = retrieveFromS3(currentValue);
                retrievedFromS3 = true;
            }

            // Step 2: Decompress the field value (original or retrieved)
            log.debug("Decompressing field at path: {}", fieldPath);
            String decompressedValue = MessageDecompressor.decompress(currentValue, compressionFormat, tryBase64Decode);

            log.debug("Decompressed content (first 100 chars): {}",
                    decompressedValue.length() > 100 ? decompressedValue.substring(0, 100) + "..." : decompressedValue);

            // Step 3: Check if decompressed value is an S3 URI (only if we didn't already
            // retrieve from S3, or if we support recursive?)
            // The original logic supported: Compressed -> Decompress -> S3 URI -> Retrieve
            // We should preserve that.
            String finalValue = decompressedValue;
            if (!retrievedFromS3 && retrieveFromS3IfUri && isS3Uri(decompressedValue)) {
                log.info("Decompressed content is an S3 URI: {}", decompressedValue.trim());
                finalValue = retrieveFromS3(decompressedValue.trim());
                log.debug("Retrieved {} bytes from S3", finalValue.length());
            }

            // Replace the field value with processed value
            if (parentNode instanceof ObjectNode && lastKey != null) {
                // Try to parse as JSON first, if it fails, treat as plain text
                JsonNode valueNode = parseAsJsonOrText(finalValue);
                ((ObjectNode) parentNode).set(lastKey, valueNode);
            }

            // Return the modified JSON
            return objectMapper.writeValueAsString(rootNode);

        } catch (IOException e) {
            log.error("Failed to process message for decompressing claim check: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Check if a string is an S3 URI.
     */
    private boolean isS3Uri(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.startsWith("s3://") && trimmed.length() > 5;
    }

    /**
     * Retrieve content from S3.
     */
    private String retrieveFromS3(String s3Uri) throws IOException {
        log.info("Retrieving content from S3: {}", s3Uri);
        byte[] s3Content = s3Client.getObjectByUri(s3Uri);
        String contentAsString = new String(s3Content, StandardCharsets.UTF_8);
        log.debug("Successfully retrieved {} bytes from S3", s3Content.length);
        return contentAsString;
    }

    /**
     * Attempts to parse a string as JSON. If successful, returns the parsed
     * JsonNode.
     * If parsing fails (not valid JSON), returns a TextNode containing the original
     * string.
     */
    private JsonNode parseAsJsonOrText(String value) {
        try {
            // Try to parse as JSON
            return objectMapper.readTree(value);
        } catch (IOException e) {
            // Not valid JSON, return as text
            log.debug("Value is not valid JSON, treating as plain text");
            return new TextNode(value);
        }
    }

    /**
     * Sets the delegate converter (useful for testing).
     */
    public void setDelegateConverter(MessageConverter delegateConverter) {
        this.delegateConverter = delegateConverter;
    }

    /**
     * Sets the S3 client (useful for testing).
     */
    public void setS3Client(S3Client s3Client) {
        this.s3Client = s3Client;
    }

    /**
     * Sets the field path (useful for testing).
     */
    public void setFieldPath(String fieldPath) {
        this.fieldPath = fieldPath;
    }

    /**
     * Sets the compression format (useful for testing).
     */
    public void setCompressionFormat(MessageDecompressor.CompressionFormat compressionFormat) {
        this.compressionFormat = compressionFormat != null ? compressionFormat
                : MessageDecompressor.CompressionFormat.AUTO;
    }

    /**
     * Sets whether to try Base64 decoding (useful for testing).
     */
    public void setTryBase64Decode(boolean tryBase64Decode) {
        this.tryBase64Decode = tryBase64Decode;
    }

    /**
     * Sets whether to retrieve from S3 if decompressed content is an S3 URI (useful
     * for testing).
     */
    public void setRetrieveFromS3IfUri(boolean retrieveFromS3IfUri) {
        this.retrieveFromS3IfUri = retrieveFromS3IfUri;
    }

    /**
     * Initialize for testing with basic defaults.
     */
    public void initializeForTesting() {
        if (this.compressionFormat == null) {
            this.compressionFormat = MessageDecompressor.CompressionFormat.AUTO;
        }
        this.retrieveFromS3IfUri = true;
    }

    /**
     * Cleanup resources.
     */
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}
