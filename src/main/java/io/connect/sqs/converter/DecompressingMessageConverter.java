package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import io.connect.sqs.util.MessageDecompressor;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Message converter wrapper that decompresses message data before delegating to another converter.
 * Supports decompression of:
 * 1. Entire message body (default)
 * 2. Specific nested JSON field paths (e.g., "detail.data")
 *
 * Automatically detects compression format (gzip, deflate, zlib) and handles Base64-encoded data.
 *
 * Usage:
 * Set message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
 * Set message.decompression.enabled=true
 * Set message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter (or any other converter)
 * Optional: message.decompression.field.path=detail.data (for nested field decompression)
 */
public class DecompressingMessageConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(DecompressingMessageConverter.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MessageConverter delegateConverter;
    private String fieldPath;
    private boolean decompressEntireBody;
    private MessageDecompressor.CompressionFormat format;
    private boolean tryBase64Decode;

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        if (delegateConverter == null) {
            initialize(config);
        }

        try {
            // Decompress the message body
            String decompressedBody = decompressMessage(message.body(), config);

            // Create a new message with decompressed body
            Message decompressedMessage = Message.builder()
                    .messageId(message.messageId())
                    .receiptHandle(message.receiptHandle())
                    .body(decompressedBody)
                    .attributes(message.attributes())
                    .messageAttributes(message.messageAttributes())
                    .md5OfBody(message.md5OfBody())
                    .build();

            // Delegate to the wrapped converter
            return delegateConverter.convert(decompressedMessage, config);

        } catch (Exception e) {
            log.error("Failed to decompress message {}: {}", message.messageId(), e.getMessage(), e);
            throw new ConnectException("Message decompression failed: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize the delegate converter and decompression settings from config.
     */
    private void initialize(SqsSourceConnectorConfig config) {
        // Get configuration values
        String delegateClass = config.getMessageDecompressionDelegateConverterClass();
        this.fieldPath = config.getMessageDecompressionFieldPath();
        this.decompressEntireBody = (fieldPath == null || fieldPath.trim().isEmpty());

        String formatStr = config.getMessageDecompressionFormat();
        this.format = MessageDecompressor.CompressionFormat.valueOf(formatStr.toUpperCase());

        this.tryBase64Decode = config.isMessageDecompressionBase64DecodeEnabled();

        // Create delegate converter instance
        try {
            Class<?> converterClass = Class.forName(delegateClass);
            this.delegateConverter = (MessageConverter) converterClass.getDeclaredConstructor().newInstance();
            log.info("Initialized DecompressingMessageConverter with delegate: {}, fieldPath: {}, format: {}",
                    delegateClass, fieldPath, format);
        } catch (Exception e) {
            throw new ConnectException("Failed to instantiate delegate converter: " + delegateClass, e);
        }
    }

    /**
     * Decompresses message body, either entirely or at a specific field path.
     */
    private String decompressMessage(String messageBody, SqsSourceConnectorConfig config) throws IOException {
        if (messageBody == null || messageBody.isEmpty()) {
            return messageBody;
        }

        if (decompressEntireBody) {
            // Decompress the entire message body
            log.debug("Decompressing entire message body");
            return MessageDecompressor.decompress(messageBody, format, tryBase64Decode);
        } else {
            // Decompress a specific field path within JSON
            log.debug("Decompressing field path: {}", fieldPath);
            return decompressFieldPath(messageBody, fieldPath);
        }
    }

    /**
     * Decompresses data at a specific JSON field path.
     * Example: "detail.data" will decompress the value at message.detail.data
     */
    private String decompressFieldPath(String jsonBody, String path) throws IOException {
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
                    // This is the last key - the field we want to decompress
                    parentNode = currentNode;
                    lastKey = key;
                    currentNode = currentNode.get(key);
                } else {
                    currentNode = currentNode.get(key);
                }
            }

            if (currentNode == null || !currentNode.isTextual()) {
                log.warn("Field at path '{}' is not a string, cannot decompress", path);
                return jsonBody;
            }

            // Decompress the field value
            String compressedValue = currentNode.asText();
            String decompressedValue = MessageDecompressor.decompress(compressedValue, format, tryBase64Decode);

            // Replace the field value with decompressed value
            if (parentNode instanceof ObjectNode && lastKey != null) {
                ((ObjectNode) parentNode).set(lastKey, new TextNode(decompressedValue));
            }

            // Return the modified JSON
            return objectMapper.writeValueAsString(rootNode);

        } catch (IOException e) {
            log.error("Failed to process JSON for field path decompression: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * Sets the delegate converter (useful for testing).
     */
    public void setDelegateConverter(MessageConverter delegateConverter) {
        this.delegateConverter = delegateConverter;
    }

    /**
     * Sets the field path (useful for testing).
     * When fieldPath is null or empty, entire body decompression is enabled.
     */
    public void setFieldPath(String fieldPath) {
        this.fieldPath = fieldPath;
        this.decompressEntireBody = (fieldPath == null || fieldPath.trim().isEmpty());
    }

    /**
     * Sets the compression format (useful for testing).
     */
    public void setFormat(MessageDecompressor.CompressionFormat format) {
        this.format = format != null ? format : MessageDecompressor.CompressionFormat.AUTO;
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
        if (this.format == null) {
            this.format = MessageDecompressor.CompressionFormat.AUTO;
        }
        this.decompressEntireBody = (this.fieldPath == null || this.fieldPath.trim().isEmpty());
    }
}
