package io.connect.sqs.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Message converter wrapper that extracts a specific field from the final converted message.
 * This is useful for extracting nested fields like detail.data from EventBridge messages.
 *
 * Example:
 * Input:  {"version":"0","detail":{"data":{"offers":[...]}}}
 * Extract: detail.data
 * Output: {"offers":[...]}
 *
 * Usage:
 * Set message.output.field.extract=detail.data
 * Set message.output.field.extract.failOnMissing=false (optional, defaults to false)
 */
public class FieldExtractorConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(FieldExtractorConverter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private MessageConverter delegateConverter;
    private String fieldPath;
    private boolean failOnMissing;

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        // Initialize on first call
        if (fieldPath == null) {
            initialize(config);
        }

        try {
            // First convert with delegate converter
            SourceRecord delegateRecord = delegateConverter.convert(message, config);
            if (delegateRecord == null) {
                return null;
            }

            // If no field path configured, return as-is
            if (fieldPath == null || fieldPath.trim().isEmpty()) {
                return delegateRecord;
            }

            // Extract field from the value
            Object value = delegateRecord.value();
            if (value == null) {
                return delegateRecord;
            }

            // Only process if value is a String (JSON)
            if (!(value instanceof String)) {
                log.debug("Value is not a String, returning original record");
                return delegateRecord;
            }

            String valueStr = (String) value;
            String extractedValue = extractField(valueStr);

            // Create new SourceRecord with extracted value
            return new SourceRecord(
                    delegateRecord.sourcePartition(),
                    delegateRecord.sourceOffset(),
                    delegateRecord.topic(),
                    delegateRecord.kafkaPartition(),
                    delegateRecord.keySchema(),
                    delegateRecord.key(),
                    delegateRecord.valueSchema(),
                    extractedValue,
                    delegateRecord.timestamp(),
                    delegateRecord.headers()
            );

        } catch (Exception e) {
            log.error("Failed to extract field from message {}: {}", message.messageId(), e.getMessage(), e);
            throw new ConnectException("Field extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Initialize the delegate converter and settings from config.
     */
    private void initialize(SqsSourceConnectorConfig config) {
        this.fieldPath = config.getMessageOutputFieldExtract();
        this.failOnMissing = config.isMessageOutputFieldExtractFailOnMissing();

        log.info("Initialized FieldExtractorConverter with fieldPath: {}, failOnMissing: {}",
                fieldPath, failOnMissing);
    }

    /**
     * Set the delegate converter (for wrapper pattern).
     */
    public void setDelegateConverter(MessageConverter delegateConverter) {
        this.delegateConverter = delegateConverter;
    }

    /**
     * Extract field from JSON string using field path.
     */
    private String extractField(String jsonStr) throws IOException {
        if (jsonStr == null || jsonStr.isEmpty()) {
            return jsonStr;
        }

        try {
            // Parse JSON
            JsonNode rootNode = objectMapper.readTree(jsonStr);

            // Navigate to the field
            List<String> pathParts = Arrays.asList(fieldPath.split("\\."));
            JsonNode currentNode = rootNode;

            for (String part : pathParts) {
                if (currentNode == null) {
                    break;
                }
                currentNode = currentNode.get(part);
            }

            // Check if field was found
            if (currentNode == null) {
                if (failOnMissing) {
                    throw new ConnectException("Field path '" + fieldPath + "' not found in message");
                } else {
                    log.debug("Field path '{}' not found, returning original message", fieldPath);
                    return jsonStr;
                }
            }

            // Convert extracted node back to JSON string
            if (currentNode.isTextual()) {
                // If it's a text node, return the text value
                return currentNode.asText();
            } else {
                // Otherwise, return as JSON string
                return objectMapper.writeValueAsString(currentNode);
            }

        } catch (IOException e) {
            log.error("Failed to parse JSON for field extraction: {}", e.getMessage());
            if (failOnMissing) {
                throw e;
            }
            // Return original if not valid JSON and failOnMissing is false
            return jsonStr;
        }
    }
}
