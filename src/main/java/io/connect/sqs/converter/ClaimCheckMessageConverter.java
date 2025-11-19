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
 * Message converter wrapper that implements the claim check pattern by retrieving large messages from S3.
 * Supports:
 * 1. Entire message body containing an S3 URI (s3://bucket/key)
 * 2. Specific nested JSON field paths containing an S3 URI (e.g., "detail.s3Key")
 * 3. Optional decompression of S3 content after retrieval
 * 4. Support for compressed data in S3
 *
 * The claim check pattern is used when messages are too large for SQS/EventBridge limits.
 * Instead of sending the full payload, a reference (S3 URI) is sent, which this converter
 * retrieves and processes.
 *
 * Usage:
 * Set message.converter.class=io.connect.sqs.converter.ClaimCheckMessageConverter
 * Set message.claimcheck.enabled=true
 * Set message.claimcheck.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
 * Optional: message.claimcheck.field.path=detail.s3Key (for nested field)
 * Optional: message.claimcheck.decompress.enabled=true (if S3 content is compressed)
 */
public class ClaimCheckMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(ClaimCheckMessageConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MessageConverter delegateConverter;
    private S3Client s3Client;
    private String fieldPath;
    private boolean retrieveEntireBody;
    private boolean decompressAfterRetrieval;
    private MessageDecompressor.CompressionFormat compressionFormat;
    private boolean tryBase64Decode;

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        if (delegateConverter == null) {
            initialize(config);
        }

        try {
            // Retrieve the message content from S3
            String retrievedBody = retrieveFromS3(message.body(), config);

            // Create a new message with retrieved body
            Message retrievedMessage = Message.builder()
                    .messageId(message.messageId())
                    .receiptHandle(message.receiptHandle())
                    .body(retrievedBody)
                    .attributes(message.attributes())
                    .messageAttributes(message.messageAttributes())
                    .md5OfBody(message.md5OfBody())
                    .build();

            // Delegate to the wrapped converter
            return delegateConverter.convert(retrievedMessage, config);

        } catch (Exception e) {
            log.error("Failed to retrieve claim check message from S3 for message {}: {}",
                    message.messageId(), e.getMessage(), e);
            throw new ConnectException("Claim check S3 retrieval failed: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize the delegate converter and claim check settings from config.
     */
    private void initialize(SqsSourceConnectorConfig config) {
        // Initialize S3 client
        this.s3Client = new S3Client(config);

        // Get configuration values
        String delegateClass = config.getMessageClaimCheckDelegateConverterClass();
        this.fieldPath = config.getMessageClaimCheckFieldPath();
        this.retrieveEntireBody = (fieldPath == null || fieldPath.trim().isEmpty());
        this.decompressAfterRetrieval = config.isMessageClaimCheckDecompressEnabled();

        if (decompressAfterRetrieval) {
            String formatStr = config.getMessageClaimCheckCompressionFormat();
            this.compressionFormat = MessageDecompressor.CompressionFormat.valueOf(formatStr.toUpperCase());
            this.tryBase64Decode = config.isMessageClaimCheckBase64DecodeEnabled();
        }

        // Create delegate converter instance
        try {
            Class<?> converterClass = Class.forName(delegateClass);
            this.delegateConverter = (MessageConverter) converterClass.getDeclaredConstructor().newInstance();
            log.info("Initialized ClaimCheckMessageConverter with delegate: {}, fieldPath: {}, decompress: {}",
                    delegateClass, fieldPath != null ? fieldPath : "<entire body>", decompressAfterRetrieval);
        } catch (Exception e) {
            throw new ConnectException("Failed to instantiate delegate converter: " + delegateClass, e);
        }
    }

    /**
     * Retrieves message content from S3, either from entire body or from a specific field path.
     */
    private String retrieveFromS3(String messageBody, SqsSourceConnectorConfig config) throws IOException {
        if (messageBody == null || messageBody.isEmpty()) {
            return messageBody;
        }

        if (retrieveEntireBody) {
            // Entire message body is an S3 URI
            log.debug("Retrieving S3 object from URI in message body");
            return retrieveAndProcessS3Content(messageBody);
        } else {
            // S3 URI is at a specific field path within JSON
            log.debug("Retrieving S3 object from field path: {}", fieldPath);
            return retrieveFromFieldPath(messageBody, fieldPath);
        }
    }

    /**
     * Retrieves S3 content from a specific JSON field path.
     * Example: "detail.s3Key" will retrieve from the S3 URI at message.detail.s3Key
     * and replace that field with the retrieved content.
     */
    private String retrieveFromFieldPath(String jsonBody, String path) throws IOException {
        try {
            // Parse JSON
            JsonNode rootNode = objectMapper.readTree(jsonBody);

            // Navigate to the field
            List<String> pathParts = Arrays.asList(path.split("\\."));
            JsonNode currentNode = rootNode;
            JsonNode parentNode = null;
            String lastKey = null;

            for (int i = 0; i < pathParts.size(); i++) {
                String key = pathParts.get(i);
                if (currentNode == null || !currentNode.has(key)) {
                    log.warn("Field path '{}' not found in message, returning original", path);
                    return jsonBody;
                }

                if (i == pathParts.size() - 1) {
                    // This is the last key - the field containing the S3 URI
                    parentNode = currentNode;
                    lastKey = key;
                    currentNode = currentNode.get(key);
                } else {
                    currentNode = currentNode.get(key);
                }
            }

            if (currentNode == null || !currentNode.isTextual()) {
                log.warn("Field at path '{}' is not a string, cannot retrieve from S3", path);
                return jsonBody;
            }

            // Get the S3 URI and retrieve content
            String s3Uri = currentNode.asText();
            String retrievedContent = retrieveAndProcessS3Content(s3Uri);

            // Replace the field value with retrieved content
            if (parentNode instanceof ObjectNode && lastKey != null) {
                ((ObjectNode) parentNode).set(lastKey, new TextNode(retrievedContent));
            }

            // Return the modified JSON
            return objectMapper.writeValueAsString(rootNode);

        } catch (IOException e) {
            log.error("Failed to process JSON for field path S3 retrieval: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Retrieves content from S3 and optionally decompresses it.
     */
    private String retrieveAndProcessS3Content(String s3Uri) throws IOException {
        if (s3Uri == null || s3Uri.trim().isEmpty()) {
            log.warn("Empty S3 URI provided, skipping retrieval");
            return s3Uri;
        }

        // Validate S3 URI format
        if (!s3Uri.trim().startsWith("s3://")) {
            log.warn("String does not appear to be an S3 URI (doesn't start with s3://): {}", s3Uri);
            // Return as-is if not an S3 URI - this allows mixed mode where some messages have S3 refs and others don't
            return s3Uri;
        }

        log.info("Retrieving content from S3: {}", s3Uri);

        // Retrieve from S3
        byte[] s3Content = s3Client.getObjectByUri(s3Uri.trim());
        String contentAsString = new String(s3Content, StandardCharsets.UTF_8);

        // Optionally decompress
        if (decompressAfterRetrieval) {
            log.debug("Decompressing S3 content with format: {}", compressionFormat);
            contentAsString = MessageDecompressor.decompress(contentAsString, compressionFormat, tryBase64Decode);
        }

        log.debug("Successfully retrieved and processed {} bytes from S3", s3Content.length);
        return contentAsString;
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
     * When fieldPath is null or empty, entire body retrieval is enabled.
     */
    public void setFieldPath(String fieldPath) {
        this.fieldPath = fieldPath;
        this.retrieveEntireBody = (fieldPath == null || fieldPath.trim().isEmpty());
    }

    /**
     * Sets whether to decompress after retrieval (useful for testing).
     */
    public void setDecompressAfterRetrieval(boolean decompressAfterRetrieval) {
        this.decompressAfterRetrieval = decompressAfterRetrieval;
    }

    /**
     * Sets the compression format (useful for testing).
     */
    public void setCompressionFormat(MessageDecompressor.CompressionFormat compressionFormat) {
        this.compressionFormat = compressionFormat != null ? compressionFormat : MessageDecompressor.CompressionFormat.AUTO;
    }

    /**
     * Sets whether to try Base64 decoding (useful for testing).
     */
    public void setTryBase64Decode(boolean tryBase64Decode) {
        this.tryBase64Decode = tryBase64Decode;
    }

    /**
     * Initialize for testing with basic defaults.
     */
    void initializeForTesting() {
        if (this.compressionFormat == null) {
            this.compressionFormat = MessageDecompressor.CompressionFormat.AUTO;
        }
        this.retrieveEntireBody = (this.fieldPath == null || this.fieldPath.trim().isEmpty());
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
