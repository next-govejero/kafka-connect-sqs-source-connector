package io.connect.sqs.converter;

import io.confluent.kafka.schemaregistry.client.CachedSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaAndValue;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Abstract base class for message converters that integrate with Confluent Schema Registry.
 * Provides common functionality for Avro, Protobuf, and JSON Schema converters.
 */
public abstract class SchemaRegistryConverter implements MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryConverter.class);

    private static final String SOURCE_PARTITION_KEY = "queue_url";
    private static final String SOURCE_OFFSET_KEY = "message_id";

    // Header keys for SQS metadata
    protected static final String HEADER_MESSAGE_ID = "sqs.message.id";
    protected static final String HEADER_RECEIPT_HANDLE = "sqs.receipt.handle";
    protected static final String HEADER_MD5_OF_BODY = "sqs.md5.of.body";
    protected static final String HEADER_SENT_TIMESTAMP = "sqs.sent.timestamp";
    protected static final String HEADER_APPROXIMATE_RECEIVE_COUNT = "sqs.approximate.receive.count";
    protected static final String HEADER_APPROXIMATE_FIRST_RECEIVE_TIMESTAMP = "sqs.approximate.first.receive.timestamp";
    protected static final String HEADER_MESSAGE_GROUP_ID = "sqs.message.group.id";
    protected static final String HEADER_MESSAGE_DEDUPLICATION_ID = "sqs.message.deduplication.id";
    protected static final String HEADER_SEQUENCE_NUMBER = "sqs.sequence.number";
    protected static final String HEADER_SCHEMA_ID = "sqs.schema.id";
    protected static final String HEADER_SCHEMA_TYPE = "sqs.schema.type";

    private static final int SCHEMA_REGISTRY_IDENTITY_MAP_CAPACITY = 100;

    protected SchemaRegistryClient schemaRegistryClient;
    protected boolean initialized = false;

    /**
     * Initialize the Schema Registry client with the provided configuration.
     * This method should be called before any conversion operations.
     *
     * @param config The connector configuration
     */
    protected void initializeSchemaRegistry(SqsSourceConnectorConfig config) {
        if (initialized) {
            return;
        }

        String schemaRegistryUrl = config.getSchemaRegistryUrl();
        if (schemaRegistryUrl == null || schemaRegistryUrl.trim().isEmpty()) {
            throw new IllegalStateException("Schema Registry URL is required for " + getSchemaType() + " converter");
        }

        Map<String, Object> schemaRegistryConfigs = new HashMap<>();
        schemaRegistryConfigs.put("schema.registry.url", schemaRegistryUrl);

        // Add authentication if configured
        String authSource = config.getSchemaRegistryBasicAuthCredentialsSource();
        if (authSource != null && !authSource.isEmpty()) {
            schemaRegistryConfigs.put("basic.auth.credentials.source", authSource);
        }

        String authUserInfo = config.getSchemaRegistryBasicAuthUserInfo();
        if (authUserInfo != null && !authUserInfo.isEmpty()) {
            schemaRegistryConfigs.put("basic.auth.user.info", authUserInfo);
        }

        // Add SSL configuration if provided
        String truststoreLocation = config.getSchemaRegistrySslTruststoreLocation();
        if (truststoreLocation != null && !truststoreLocation.isEmpty()) {
            schemaRegistryConfigs.put("schema.registry.ssl.truststore.location", truststoreLocation);
        }

        String truststorePassword = config.getSchemaRegistrySslTruststorePassword();
        if (truststorePassword != null && !truststorePassword.isEmpty()) {
            schemaRegistryConfigs.put("schema.registry.ssl.truststore.password", truststorePassword);
        }

        // Add schema evolution configuration
        schemaRegistryConfigs.put("auto.register.schemas", config.isSchemaAutoRegister());
        schemaRegistryConfigs.put("use.latest.version", config.isSchemaUseLatestVersion());
        schemaRegistryConfigs.put("value.subject.name.strategy", config.getSchemaSubjectNameStrategy());

        this.schemaRegistryClient = new CachedSchemaRegistryClient(
                schemaRegistryUrl,
                SCHEMA_REGISTRY_IDENTITY_MAP_CAPACITY,
                schemaRegistryConfigs
        );

        log.info("Initialized {} Schema Registry client for {}", getSchemaType(), schemaRegistryUrl);
        initialized = true;
    }

    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        initializeSchemaRegistry(config);

        log.debug("Converting SQS message {} using {} converter", message.messageId(), getSchemaType());

        // Create source partition and offset
        Map<String, String> sourcePartition = new HashMap<>();
        sourcePartition.put(SOURCE_PARTITION_KEY, config.getSqsQueueUrl());

        Map<String, String> sourceOffset = new HashMap<>();
        sourceOffset.put(SOURCE_OFFSET_KEY, message.messageId());

        // Create headers with SQS metadata
        Headers headers = createHeaders(message, config);

        // Get Kafka topic and partition
        String topic = config.getKafkaTopic();
        Integer partition = config.getKafkaTopicPartition();

        // Convert the message body to schema-based value
        SchemaAndValue keySchemaAndValue = convertKey(message, config);
        SchemaAndValue valueSchemaAndValue = convertValue(message, config);

        return new SourceRecord(
                sourcePartition,
                sourceOffset,
                topic,
                partition,
                keySchemaAndValue.schema(),
                keySchemaAndValue.value(),
                valueSchemaAndValue.schema(),
                valueSchemaAndValue.value(),
                System.currentTimeMillis(),
                headers
        );
    }

    /**
     * Convert the SQS message body to a schema-based value.
     *
     * @param message The SQS message
     * @param config The connector configuration
     * @return Schema and value for the Kafka record value
     */
    protected abstract SchemaAndValue convertValue(Message message, SqsSourceConnectorConfig config);

    /**
     * Convert the SQS message key to a schema-based key.
     * Default implementation uses MessageGroupId for FIFO queues or messageId.
     *
     * @param message The SQS message
     * @param config The connector configuration
     * @return Schema and value for the Kafka record key
     */
    protected SchemaAndValue convertKey(Message message, SqsSourceConnectorConfig config) {
        String key = determineKey(message, config);
        return new SchemaAndValue(Schema.STRING_SCHEMA, key);
    }

    /**
     * Get the schema type name (e.g., "Avro", "Protobuf", "JSON Schema").
     *
     * @return The schema type name
     */
    protected abstract String getSchemaType();

    /**
     * Determines the Kafka record key based on message type.
     * For FIFO queues, uses MessageGroupId to preserve ordering.
     * For standard queues, uses message ID.
     */
    protected String determineKey(Message message, SqsSourceConnectorConfig config) {
        if (config.isFifoQueue()) {
            Map<String, String> attributes = message.attributesAsStrings();
            if (attributes != null && attributes.containsKey("MessageGroupId")) {
                String messageGroupId = attributes.get("MessageGroupId");
                log.debug("Using MessageGroupId {} as key for FIFO message {}", messageGroupId, message.messageId());
                return messageGroupId;
            }
            log.warn("FIFO queue configured but MessageGroupId not found for message {}, using messageId as key",
                    message.messageId());
        }
        return message.messageId();
    }

    /**
     * Creates Kafka headers with SQS metadata.
     */
    protected Headers createHeaders(Message message, SqsSourceConnectorConfig config) {
        ConnectHeaders headers = new ConnectHeaders();

        // Add schema type header
        headers.addString(HEADER_SCHEMA_TYPE, getSchemaType());

        // Add basic message metadata
        headers.addString(HEADER_MESSAGE_ID, message.messageId());

        if (message.receiptHandle() != null) {
            headers.addString(HEADER_RECEIPT_HANDLE, message.receiptHandle());
        }

        if (message.md5OfBody() != null) {
            headers.addString(HEADER_MD5_OF_BODY, message.md5OfBody());
        }

        // Add system attributes
        Map<String, String> attributes = message.attributesAsStrings();
        if (attributes != null) {
            if (attributes.containsKey("SentTimestamp")) {
                headers.addString(HEADER_SENT_TIMESTAMP, attributes.get("SentTimestamp"));
            }

            if (attributes.containsKey("ApproximateReceiveCount")) {
                headers.addString(HEADER_APPROXIMATE_RECEIVE_COUNT,
                        attributes.get("ApproximateReceiveCount"));
            }

            if (attributes.containsKey("ApproximateFirstReceiveTimestamp")) {
                headers.addString(HEADER_APPROXIMATE_FIRST_RECEIVE_TIMESTAMP,
                        attributes.get("ApproximateFirstReceiveTimestamp"));
            }

            // Add FIFO-specific attributes
            if (attributes.containsKey("MessageGroupId")) {
                headers.addString(HEADER_MESSAGE_GROUP_ID, attributes.get("MessageGroupId"));
            }

            if (attributes.containsKey("MessageDeduplicationId")) {
                headers.addString(HEADER_MESSAGE_DEDUPLICATION_ID, attributes.get("MessageDeduplicationId"));
            }

            if (attributes.containsKey("SequenceNumber")) {
                headers.addString(HEADER_SEQUENCE_NUMBER, attributes.get("SequenceNumber"));
            }
        }

        // Add message attributes if enabled
        if (config.isSqsMessageAttributesEnabled() && message.hasMessageAttributes()) {
            addMessageAttributeHeaders(headers, message.messageAttributes());
        }

        return headers;
    }

    private void addMessageAttributeHeaders(ConnectHeaders headers, Map<String, MessageAttributeValue> attributes) {
        for (Map.Entry<String, MessageAttributeValue> entry : attributes.entrySet()) {
            String headerKey = "sqs.message.attribute." + entry.getKey();
            MessageAttributeValue value = entry.getValue();

            String dataType = value.dataType();
            String stringValue = value.stringValue();

            if (stringValue != null) {
                headers.addString(headerKey, stringValue);
            } else if (value.binaryValue() != null) {
                headers.addBytes(headerKey, value.binaryValue().asByteArray());
            }

            headers.addString(headerKey + ".type", dataType);
        }
    }

    /**
     * Get the subject name for schema registration based on topic and subject naming strategy.
     *
     * @param topic The Kafka topic
     * @param isKey Whether this is for key or value schema
     * @return The subject name
     */
    protected String getSubjectName(String topic, boolean isKey) {
        String suffix = isKey ? "-key" : "-value";
        return topic + suffix;
    }

    /**
     * Validates that schema registry is properly initialized.
     *
     * @throws IllegalStateException if not initialized
     */
    protected void ensureInitialized() {
        if (!initialized || schemaRegistryClient == null) {
            throw new IllegalStateException(getSchemaType() + " converter not properly initialized. "
                    + "Schema Registry client is required.");
        }
    }

    /**
     * Parse JSON string to detect schema from message content.
     * Useful for automatic schema inference.
     *
     * @param jsonContent The JSON content
     * @return A map representation of the JSON
     */
    protected Map<String, Object> parseJsonToMap(String jsonContent) {
        // Simple JSON parsing for schema detection
        // In production, use Jackson or similar library
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(jsonContent, Map.class);
        } catch (Exception e) {
            throw new SerializationException("Failed to parse JSON content: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch schema by ID from Schema Registry.
     *
     * @param schemaId The schema ID
     * @return The schema string
     * @throws SerializationException if schema cannot be fetched
     */
    protected String fetchSchemaById(int schemaId) {
        ensureInitialized();
        try {
            return schemaRegistryClient.getById(schemaId).toString();
        } catch (IOException | RestClientException e) {
            throw new SerializationException("Failed to fetch schema with ID " + schemaId, e);
        }
    }

    /**
     * Fetch latest schema version for a subject.
     *
     * @param subject The subject name
     * @return The schema string
     * @throws SerializationException if schema cannot be fetched
     */
    protected String fetchLatestSchema(String subject) {
        ensureInitialized();
        try {
            return schemaRegistryClient.getLatestSchemaMetadata(subject).getSchema();
        } catch (IOException | RestClientException e) {
            throw new SerializationException("Failed to fetch latest schema for subject " + subject, e);
        }
    }

    /**
     * Register a new schema with Schema Registry.
     *
     * @param subject The subject name
     * @param schemaString The schema string
     * @return The schema ID
     * @throws SerializationException if schema cannot be registered
     */
    protected int registerSchema(String subject, Object schema) {
        ensureInitialized();
        try {
            return schemaRegistryClient.register(subject,
                    (io.confluent.kafka.schemaregistry.ParsedSchema) schema);
        } catch (IOException | RestClientException e) {
            throw new SerializationException("Failed to register schema for subject " + subject, e);
        }
    }

    /**
     * Close the Schema Registry client and release resources.
     */
    public void close() {
        if (schemaRegistryClient != null) {
            try {
                // SchemaRegistryClient doesn't have a close method, but we can clear the reference
                schemaRegistryClient = null;
                initialized = false;
                log.info("Closed {} Schema Registry converter", getSchemaType());
            } catch (Exception e) {
                log.warn("Error closing Schema Registry client", e);
            }
        }
    }
}
