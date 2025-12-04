package io.connect.sqs.config;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.apache.kafka.common.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Configuration for the SQS Source Connector.
 */
public class SqsSourceConnectorConfig extends AbstractConfig {

    private static final Logger log = LoggerFactory.getLogger(SqsSourceConnectorConfig.class);

    // AWS Configuration
    public static final String AWS_REGION_CONFIG = "aws.region";
    private static final String AWS_REGION_DOC = "AWS region for SQS service";
    private static final String AWS_REGION_DEFAULT = "us-east-1";

    public static final String AWS_ACCESS_KEY_ID_CONFIG = "aws.access.key.id";
    private static final String AWS_ACCESS_KEY_ID_DOC = "AWS access key ID";

    public static final String AWS_SECRET_ACCESS_KEY_CONFIG = "aws.secret.access.key";
    private static final String AWS_SECRET_ACCESS_KEY_DOC = "AWS secret access key";

    public static final String AWS_ASSUME_ROLE_ARN_CONFIG = "aws.assume.role.arn";
    private static final String AWS_ASSUME_ROLE_ARN_DOC = "AWS IAM role ARN to assume for SQS access";

    public static final String AWS_STS_ROLE_SESSION_NAME_CONFIG = "aws.sts.role.session.name";
    private static final String AWS_STS_ROLE_SESSION_NAME_DOC = "Session name for assumed role";
    private static final String AWS_STS_ROLE_SESSION_NAME_DEFAULT = "kafka-connect-sqs";

    public static final String AWS_STS_ROLE_EXTERNAL_ID_CONFIG = "aws.sts.role.external.id";
    private static final String AWS_STS_ROLE_EXTERNAL_ID_DOC = "External ID for assuming role (for third-party access)";

    public static final String AWS_CREDENTIALS_PROFILE_CONFIG = "aws.credentials.profile";
    private static final String AWS_CREDENTIALS_PROFILE_DOC = "AWS credentials profile name to use from credentials file";

    public static final String AWS_CREDENTIALS_FILE_PATH_CONFIG = "aws.credentials.file.path";
    private static final String AWS_CREDENTIALS_FILE_PATH_DOC = "Path to AWS credentials file (default: ~/.aws/credentials)";

    public static final String AWS_ENDPOINT_OVERRIDE_CONFIG = "aws.endpoint.override";
    private static final String AWS_ENDPOINT_OVERRIDE_DOC = "Override AWS SQS endpoint (useful for LocalStack or custom endpoints)";

    // SQS Configuration
    public static final String SQS_QUEUE_URL_CONFIG = "sqs.queue.url";
    private static final String SQS_QUEUE_URL_DOC = "AWS SQS queue URL to consume messages from. For single queue mode, use this property. For multi-queue mode, use sqs.queue.urls instead.";

    public static final String SQS_QUEUE_URLS_CONFIG = "sqs.queue.urls";
    private static final String SQS_QUEUE_URLS_DOC = "Comma-separated list of AWS SQS queue URLs to consume from. Each queue gets its own task for parallel processing. Example: https://sqs.us-east-1.amazonaws.com/123456789/queue1,https://sqs.us-east-1.amazonaws.com/123456789/queue2";

    public static final String SQS_MAX_MESSAGES_CONFIG = "sqs.max.messages";
    private static final String SQS_MAX_MESSAGES_DOC = "Maximum number of messages to retrieve in a single batch (1-10)";
    private static final int SQS_MAX_MESSAGES_DEFAULT = 10;

    public static final String SQS_WAIT_TIME_SECONDS_CONFIG = "sqs.wait.time.seconds";
    private static final String SQS_WAIT_TIME_SECONDS_DOC = "Long polling wait time in seconds (0-20)";
    private static final int SQS_WAIT_TIME_SECONDS_DEFAULT = 10;

    public static final String SQS_VISIBILITY_TIMEOUT_SECONDS_CONFIG = "sqs.visibility.timeout.seconds";
    private static final String SQS_VISIBILITY_TIMEOUT_SECONDS_DOC = "Message visibility timeout in seconds";
    private static final int SQS_VISIBILITY_TIMEOUT_SECONDS_DEFAULT = 30;

    public static final String SQS_MESSAGE_ATTRIBUTES_ENABLED_CONFIG = "sqs.message.attributes.enabled";
    private static final String SQS_MESSAGE_ATTRIBUTES_ENABLED_DOC = "Include SQS message attributes in Kafka record headers";
    private static final boolean SQS_MESSAGE_ATTRIBUTES_ENABLED_DEFAULT = true;

    public static final String SQS_DELETE_MESSAGES_CONFIG = "sqs.delete.messages";
    private static final String SQS_DELETE_MESSAGES_DOC = "Automatically delete messages from SQS after successful processing";
    private static final boolean SQS_DELETE_MESSAGES_DEFAULT = true;

    // Message Filtering Configuration
    public static final String SQS_MESSAGE_ATTRIBUTE_FILTER_NAMES_CONFIG = "sqs.message.attribute.filter.names";
    private static final String SQS_MESSAGE_ATTRIBUTE_FILTER_NAMES_DOC = "Comma-separated list of specific message attribute names to retrieve. If empty, retrieves all attributes when sqs.message.attributes.enabled is true. Example: Type,Priority,Environment";

    public static final String SQS_MESSAGE_FILTER_POLICY_CONFIG = "sqs.message.filter.policy";
    private static final String SQS_MESSAGE_FILTER_POLICY_DOC = "JSON filter policy to filter messages based on message attributes. Supports 'exact', 'prefix', 'exists' operators. Example: {\"Type\":[\"order\",\"payment\"],\"Environment\":[{\"prefix\":\"prod\"}]}";

    // Kafka Configuration
    public static final String KAFKA_TOPIC_CONFIG = "kafka.topic";
    private static final String KAFKA_TOPIC_DOC = "Kafka topic to send messages to";

    public static final String KAFKA_TOPIC_PARTITION_CONFIG = "kafka.topic.partition";
    private static final String KAFKA_TOPIC_PARTITION_DOC = "Specific Kafka partition to send messages to (optional)";

    // SCRAM Authentication Configuration
    public static final String SASL_MECHANISM_CONFIG = "sasl.mechanism";
    private static final String SASL_MECHANISM_DOC = "SASL mechanism for Kafka authentication";
    private static final String SASL_MECHANISM_DEFAULT = "SCRAM-SHA-512";

    public static final String SASL_JAAS_CONFIG = "sasl.jaas.config";
    private static final String SASL_JAAS_CONFIG_DOC = "JAAS configuration for SASL authentication";

    public static final String SECURITY_PROTOCOL_CONFIG = "security.protocol";
    private static final String SECURITY_PROTOCOL_DOC = "Security protocol for Kafka connections";
    private static final String SECURITY_PROTOCOL_DEFAULT = "SASL_SSL";

    // Error Handling Configuration
    public static final String DLQ_TOPIC_CONFIG = "dlq.topic";
    private static final String DLQ_TOPIC_DOC = "Dead letter queue topic for messages that fail processing";

    public static final String MAX_RETRIES_CONFIG = "max.retries";
    private static final String MAX_RETRIES_DOC = "Maximum number of retries for failed messages";
    private static final int MAX_RETRIES_DEFAULT = 3;

    public static final String RETRY_BACKOFF_MS_CONFIG = "retry.backoff.ms";
    private static final String RETRY_BACKOFF_MS_DOC = "Backoff time in milliseconds between retries";
    private static final long RETRY_BACKOFF_MS_DEFAULT = 1000L;

    // FIFO Queue Configuration
    public static final String SQS_FIFO_QUEUE_CONFIG = "sqs.fifo.queue";
    private static final String SQS_FIFO_QUEUE_DOC = "Enable FIFO queue support. When true, preserves message ordering using MessageGroupId as Kafka partition key";
    private static final boolean SQS_FIFO_QUEUE_DEFAULT = false;

    public static final String SQS_FIFO_AUTO_DETECT_CONFIG = "sqs.fifo.auto.detect";
    private static final String SQS_FIFO_AUTO_DETECT_DOC = "Automatically detect FIFO queue based on .fifo suffix in queue URL";
    private static final boolean SQS_FIFO_AUTO_DETECT_DEFAULT = true;

    public static final String SQS_FIFO_DEDUPLICATION_ENABLED_CONFIG = "sqs.fifo.deduplication.enabled";
    private static final String SQS_FIFO_DEDUPLICATION_ENABLED_DOC = "Enable message deduplication tracking for FIFO queues to prevent duplicate processing";
    private static final boolean SQS_FIFO_DEDUPLICATION_ENABLED_DEFAULT = true;

    public static final String SQS_FIFO_DEDUPLICATION_WINDOW_MS_CONFIG = "sqs.fifo.deduplication.window.ms";
    private static final String SQS_FIFO_DEDUPLICATION_WINDOW_MS_DOC = "Time window in milliseconds to track message deduplication IDs (default: 5 minutes)";
    private static final long SQS_FIFO_DEDUPLICATION_WINDOW_MS_DEFAULT = 300000L;

    // Polling Configuration
    public static final String POLL_INTERVAL_MS_CONFIG = "poll.interval.ms";
    private static final String POLL_INTERVAL_MS_DOC = "Interval in milliseconds between SQS polls";
    private static final long POLL_INTERVAL_MS_DEFAULT = 1000L;

    // Message Format Configuration
    public static final String MESSAGE_CONVERTER_CLASS_CONFIG = "message.converter.class";
    private static final String MESSAGE_CONVERTER_CLASS_DOC = "Class for converting SQS messages to Kafka records";
    private static final String MESSAGE_CONVERTER_CLASS_DEFAULT = "io.connect.sqs.converter.DefaultMessageConverter";

    // Schema Registry Configuration
    public static final String SCHEMA_REGISTRY_URL_CONFIG = "schema.registry.url";
    private static final String SCHEMA_REGISTRY_URL_DOC = "URL of the Confluent Schema Registry. Required when using Avro, Protobuf, or JSON Schema converters";

    public static final String VALUE_SCHEMA_ID_CONFIG = "value.schema.id";
    private static final String VALUE_SCHEMA_ID_DOC = "Schema ID to use for value serialization. If not specified, schema will be registered automatically based on message structure";

    public static final String KEY_SCHEMA_ID_CONFIG = "key.schema.id";
    private static final String KEY_SCHEMA_ID_DOC = "Schema ID to use for key serialization. Optional, defaults to string schema";

    public static final String SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_CONFIG = "schema.registry.basic.auth.credentials.source";
    private static final String SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_DOC = "Source of credentials for Schema Registry basic auth (USER_INFO, SASL_INHERIT)";
    private static final String SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_DEFAULT = "USER_INFO";

    public static final String SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_CONFIG = "schema.registry.basic.auth.user.info";
    private static final String SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_DOC = "User info for Schema Registry basic auth in format 'username:password'";

    public static final String SCHEMA_REGISTRY_SSL_TRUSTSTORE_LOCATION_CONFIG = "schema.registry.ssl.truststore.location";
    private static final String SCHEMA_REGISTRY_SSL_TRUSTSTORE_LOCATION_DOC = "Location of the truststore for Schema Registry SSL connections";

    public static final String SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_CONFIG = "schema.registry.ssl.truststore.password";
    private static final String SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_DOC = "Password for the Schema Registry SSL truststore";

    public static final String SCHEMA_AUTO_REGISTER_CONFIG = "schema.auto.register";
    private static final String SCHEMA_AUTO_REGISTER_DOC = "Enable automatic schema registration with Schema Registry";
    private static final boolean SCHEMA_AUTO_REGISTER_DEFAULT = true;

    public static final String SCHEMA_USE_LATEST_VERSION_CONFIG = "schema.use.latest.version";
    private static final String SCHEMA_USE_LATEST_VERSION_DOC = "Use latest schema version from registry instead of specific ID";
    private static final boolean SCHEMA_USE_LATEST_VERSION_DEFAULT = false;

    public static final String SCHEMA_SUBJECT_NAME_STRATEGY_CONFIG = "schema.subject.name.strategy";
    private static final String SCHEMA_SUBJECT_NAME_STRATEGY_DOC = "Strategy for naming schemas in Schema Registry (TopicNameStrategy, RecordNameStrategy, TopicRecordNameStrategy)";
    private static final String SCHEMA_SUBJECT_NAME_STRATEGY_DEFAULT = "io.confluent.kafka.serializers.subject.TopicNameStrategy";

    // Message Decompression Configuration
    public static final String MESSAGE_DECOMPRESSION_ENABLED_CONFIG = "message.decompression.enabled";
    private static final String MESSAGE_DECOMPRESSION_ENABLED_DOC = "Enable automatic decompression of compressed message data (gzip, deflate, zlib)";
    private static final boolean MESSAGE_DECOMPRESSION_ENABLED_DEFAULT = false;

    public static final String MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_CONFIG = "message.decompression.delegate.converter.class";
    private static final String MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_DOC = "The MessageConverter class to delegate to after decompression. Required when message.decompression.enabled is true";
    private static final String MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_DEFAULT = "io.connect.sqs.converter.DefaultMessageConverter";

    public static final String MESSAGE_DECOMPRESSION_FIELD_PATH_CONFIG = "message.decompression.field.path";
    private static final String MESSAGE_DECOMPRESSION_FIELD_PATH_DOC = "JSON field path to decompress (e.g., 'detail.data'). If not specified, decompresses entire message body. Use dot notation for nested fields.";

    public static final String MESSAGE_DECOMPRESSION_FORMAT_CONFIG = "message.decompression.format";
    private static final String MESSAGE_DECOMPRESSION_FORMAT_DOC = "Compression format to use: AUTO (auto-detect), GZIP, DEFLATE, ZLIB. Default is AUTO.";
    private static final String MESSAGE_DECOMPRESSION_FORMAT_DEFAULT = "AUTO";

    public static final String MESSAGE_DECOMPRESSION_BASE64_DECODE_CONFIG = "message.decompression.base64.decode";
    private static final String MESSAGE_DECOMPRESSION_BASE64_DECODE_DOC = "Attempt to Base64-decode data before decompression (common for compressed data in JSON)";
    private static final boolean MESSAGE_DECOMPRESSION_BASE64_DECODE_DEFAULT = true;

    // Message Claim Check Pattern Configuration
    public static final String MESSAGE_CLAIMCHECK_ENABLED_CONFIG = "message.claimcheck.enabled";
    private static final String MESSAGE_CLAIMCHECK_ENABLED_DOC = "Enable claim check pattern to retrieve large messages from S3 using S3 URIs (s3://bucket/key)";
    private static final boolean MESSAGE_CLAIMCHECK_ENABLED_DEFAULT = false;

    public static final String MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_CONFIG = "message.claimcheck.delegate.converter.class";
    private static final String MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_DOC = "The MessageConverter class to delegate to after S3 retrieval. Required when message.claimcheck.enabled is true";
    private static final String MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_DEFAULT = "io.connect.sqs.converter.DefaultMessageConverter";

    public static final String MESSAGE_CLAIMCHECK_FIELD_PATH_CONFIG = "message.claimcheck.field.path";
    private static final String MESSAGE_CLAIMCHECK_FIELD_PATH_DOC = "JSON field path containing S3 URI (e.g., 'detail.s3Key'). If not specified, treats entire message body as S3 URI. Use dot notation for nested fields.";

    public static final String MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_CONFIG = "message.claimcheck.decompress.enabled";
    private static final String MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_DOC = "Decompress data after retrieving from S3 (useful when S3 content is compressed)";
    private static final boolean MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_DEFAULT = false;

    public static final String MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_CONFIG = "message.claimcheck.compression.format";
    private static final String MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_DOC = "Compression format for S3 content: AUTO (auto-detect), GZIP, DEFLATE, ZLIB. Default is AUTO. Only used when message.claimcheck.decompress.enabled is true.";
    private static final String MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_DEFAULT = "AUTO";

    public static final String MESSAGE_CLAIMCHECK_BASE64_DECODE_CONFIG = "message.claimcheck.base64.decode";
    private static final String MESSAGE_CLAIMCHECK_BASE64_DECODE_DOC = "Attempt to Base64-decode S3 content before decompression. Only used when message.claimcheck.decompress.enabled is true.";
    private static final boolean MESSAGE_CLAIMCHECK_BASE64_DECODE_DEFAULT = true;

    // Message Output Field Extraction Configuration
    public static final String MESSAGE_OUTPUT_FIELD_EXTRACT_CONFIG = "message.output.field.extract";
    private static final String MESSAGE_OUTPUT_FIELD_EXTRACT_DOC = "JSON field path to extract from the final converted message before sending to Kafka (e.g., 'detail.data'). If not specified, sends the entire message. Use dot notation for nested fields.";

    public static final String MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_CONFIG = "message.output.field.extract.failOnMissing";
    private static final String MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_DOC = "If true, fails when the specified field path does not exist. If false, returns the original message when field is missing.";
    private static final boolean MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_DEFAULT = false;

    public static final ConfigDef CONFIG_DEF = createConfigDef();

    private static ConfigDef createConfigDef() {
        ConfigDef configDef = new ConfigDef();

        // AWS Group
        final String awsGroup = "AWS";
        int awsGroupOrder = 0;

        configDef.define(
                AWS_REGION_CONFIG,
                Type.STRING,
                AWS_REGION_DEFAULT,
                Importance.HIGH,
                AWS_REGION_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.MEDIUM,
                "AWS Region"
        );

        configDef.define(
                AWS_ACCESS_KEY_ID_CONFIG,
                Type.STRING,
                null,
                Importance.MEDIUM,
                AWS_ACCESS_KEY_ID_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS Access Key ID"
        );

        configDef.define(
                AWS_SECRET_ACCESS_KEY_CONFIG,
                Type.PASSWORD,
                null,
                Importance.MEDIUM,
                AWS_SECRET_ACCESS_KEY_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS Secret Access Key"
        );

        configDef.define(
                AWS_ASSUME_ROLE_ARN_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                AWS_ASSUME_ROLE_ARN_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS Assume Role ARN"
        );

        configDef.define(
                AWS_STS_ROLE_SESSION_NAME_CONFIG,
                Type.STRING,
                AWS_STS_ROLE_SESSION_NAME_DEFAULT,
                Importance.LOW,
                AWS_STS_ROLE_SESSION_NAME_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.MEDIUM,
                "AWS STS Role Session Name"
        );

        configDef.define(
                AWS_STS_ROLE_EXTERNAL_ID_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                AWS_STS_ROLE_EXTERNAL_ID_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS STS Role External ID"
        );

        configDef.define(
                AWS_CREDENTIALS_PROFILE_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                AWS_CREDENTIALS_PROFILE_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.MEDIUM,
                "AWS Credentials Profile"
        );

        configDef.define(
                AWS_CREDENTIALS_FILE_PATH_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                AWS_CREDENTIALS_FILE_PATH_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS Credentials File Path"
        );

        configDef.define(
                AWS_ENDPOINT_OVERRIDE_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                AWS_ENDPOINT_OVERRIDE_DOC,
                awsGroup,
                ++awsGroupOrder,
                Width.LONG,
                "AWS Endpoint Override"
        );

        // SQS Group
        final String sqsGroup = "SQS";
        int sqsGroupOrder = 0;

        configDef.define(
                SQS_QUEUE_URL_CONFIG,
                Type.STRING,
                null,
                Importance.HIGH,
                SQS_QUEUE_URL_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.LONG,
                "SQS Queue URL"
        );

        configDef.define(
                SQS_QUEUE_URLS_CONFIG,
                Type.STRING,
                null,
                Importance.HIGH,
                SQS_QUEUE_URLS_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.LONG,
                "SQS Queue URLs"
        );

        configDef.define(
                SQS_MAX_MESSAGES_CONFIG,
                Type.INT,
                SQS_MAX_MESSAGES_DEFAULT,
                ConfigDef.Range.between(1, 10),
                Importance.MEDIUM,
                SQS_MAX_MESSAGES_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.SHORT,
                "SQS Max Messages"
        );

        configDef.define(
                SQS_WAIT_TIME_SECONDS_CONFIG,
                Type.INT,
                SQS_WAIT_TIME_SECONDS_DEFAULT,
                ConfigDef.Range.between(0, 20),
                Importance.MEDIUM,
                SQS_WAIT_TIME_SECONDS_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.SHORT,
                "SQS Wait Time Seconds"
        );

        configDef.define(
                SQS_VISIBILITY_TIMEOUT_SECONDS_CONFIG,
                Type.INT,
                SQS_VISIBILITY_TIMEOUT_SECONDS_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.MEDIUM,
                SQS_VISIBILITY_TIMEOUT_SECONDS_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.SHORT,
                "SQS Visibility Timeout Seconds"
        );

        configDef.define(
                SQS_MESSAGE_ATTRIBUTES_ENABLED_CONFIG,
                Type.BOOLEAN,
                SQS_MESSAGE_ATTRIBUTES_ENABLED_DEFAULT,
                Importance.LOW,
                SQS_MESSAGE_ATTRIBUTES_ENABLED_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.SHORT,
                "SQS Message Attributes Enabled"
        );

        configDef.define(
                SQS_DELETE_MESSAGES_CONFIG,
                Type.BOOLEAN,
                SQS_DELETE_MESSAGES_DEFAULT,
                Importance.HIGH,
                SQS_DELETE_MESSAGES_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.SHORT,
                "SQS Delete Messages"
        );

        configDef.define(
                SQS_MESSAGE_ATTRIBUTE_FILTER_NAMES_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                SQS_MESSAGE_ATTRIBUTE_FILTER_NAMES_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.LONG,
                "SQS Message Attribute Filter Names"
        );

        configDef.define(
                SQS_MESSAGE_FILTER_POLICY_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                SQS_MESSAGE_FILTER_POLICY_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.LONG,
                "SQS Message Filter Policy"
        );

        // Kafka Group
        final String kafkaGroup = "Kafka";
        int kafkaGroupOrder = 0;

        configDef.define(
                KAFKA_TOPIC_CONFIG,
                Type.STRING,
                Importance.HIGH,
                KAFKA_TOPIC_DOC,
                kafkaGroup,
                ++kafkaGroupOrder,
                Width.MEDIUM,
                "Kafka Topic"
        );

        configDef.define(
                KAFKA_TOPIC_PARTITION_CONFIG,
                Type.INT,
                null,
                Importance.LOW,
                KAFKA_TOPIC_PARTITION_DOC,
                kafkaGroup,
                ++kafkaGroupOrder,
                Width.SHORT,
                "Kafka Topic Partition"
        );

        configDef.define(
                SASL_MECHANISM_CONFIG,
                Type.STRING,
                SASL_MECHANISM_DEFAULT,
                Importance.HIGH,
                SASL_MECHANISM_DOC,
                kafkaGroup,
                ++kafkaGroupOrder,
                Width.MEDIUM,
                "SASL Mechanism"
        );

        configDef.define(
                SASL_JAAS_CONFIG,
                Type.PASSWORD,
                null,
                Importance.HIGH,
                SASL_JAAS_CONFIG_DOC,
                kafkaGroup,
                ++kafkaGroupOrder,
                Width.LONG,
                "SASL JAAS Config"
        );

        configDef.define(
                SECURITY_PROTOCOL_CONFIG,
                Type.STRING,
                SECURITY_PROTOCOL_DEFAULT,
                Importance.HIGH,
                SECURITY_PROTOCOL_DOC,
                kafkaGroup,
                ++kafkaGroupOrder,
                Width.MEDIUM,
                "Security Protocol"
        );

        // Error Handling Group
        final String errorGroup = "Error Handling";
        int errorGroupOrder = 0;

        configDef.define(
                DLQ_TOPIC_CONFIG,
                Type.STRING,
                null,
                Importance.MEDIUM,
                DLQ_TOPIC_DOC,
                errorGroup,
                ++errorGroupOrder,
                Width.MEDIUM,
                "Dead Letter Queue Topic"
        );

        configDef.define(
                MAX_RETRIES_CONFIG,
                Type.INT,
                MAX_RETRIES_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.MEDIUM,
                MAX_RETRIES_DOC,
                errorGroup,
                ++errorGroupOrder,
                Width.SHORT,
                "Max Retries"
        );

        configDef.define(
                RETRY_BACKOFF_MS_CONFIG,
                Type.LONG,
                RETRY_BACKOFF_MS_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.MEDIUM,
                RETRY_BACKOFF_MS_DOC,
                errorGroup,
                ++errorGroupOrder,
                Width.SHORT,
                "Retry Backoff (ms)"
        );

        // FIFO Queue Group
        final String fifoGroup = "FIFO Queue";
        int fifoGroupOrder = 0;

        configDef.define(
                SQS_FIFO_QUEUE_CONFIG,
                Type.BOOLEAN,
                SQS_FIFO_QUEUE_DEFAULT,
                Importance.MEDIUM,
                SQS_FIFO_QUEUE_DOC,
                fifoGroup,
                ++fifoGroupOrder,
                Width.SHORT,
                "FIFO Queue Enabled"
        );

        configDef.define(
                SQS_FIFO_AUTO_DETECT_CONFIG,
                Type.BOOLEAN,
                SQS_FIFO_AUTO_DETECT_DEFAULT,
                Importance.LOW,
                SQS_FIFO_AUTO_DETECT_DOC,
                fifoGroup,
                ++fifoGroupOrder,
                Width.SHORT,
                "FIFO Auto Detect"
        );

        configDef.define(
                SQS_FIFO_DEDUPLICATION_ENABLED_CONFIG,
                Type.BOOLEAN,
                SQS_FIFO_DEDUPLICATION_ENABLED_DEFAULT,
                Importance.MEDIUM,
                SQS_FIFO_DEDUPLICATION_ENABLED_DOC,
                fifoGroup,
                ++fifoGroupOrder,
                Width.SHORT,
                "FIFO Deduplication Enabled"
        );

        configDef.define(
                SQS_FIFO_DEDUPLICATION_WINDOW_MS_CONFIG,
                Type.LONG,
                SQS_FIFO_DEDUPLICATION_WINDOW_MS_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.LOW,
                SQS_FIFO_DEDUPLICATION_WINDOW_MS_DOC,
                fifoGroup,
                ++fifoGroupOrder,
                Width.MEDIUM,
                "FIFO Deduplication Window (ms)"
        );

        // Polling Group
        final String pollingGroup = "Polling";
        int pollingGroupOrder = 0;

        configDef.define(
                POLL_INTERVAL_MS_CONFIG,
                Type.LONG,
                POLL_INTERVAL_MS_DEFAULT,
                ConfigDef.Range.atLeast(0),
                Importance.LOW,
                POLL_INTERVAL_MS_DOC,
                pollingGroup,
                ++pollingGroupOrder,
                Width.SHORT,
                "Poll Interval (ms)"
        );

        // Message Format Group
        final String formatGroup = "Message Format";
        int formatGroupOrder = 0;

        configDef.define(
                MESSAGE_CONVERTER_CLASS_CONFIG,
                Type.STRING,
                MESSAGE_CONVERTER_CLASS_DEFAULT,
                Importance.LOW,
                MESSAGE_CONVERTER_CLASS_DOC,
                formatGroup,
                ++formatGroupOrder,
                Width.LONG,
                "Message Converter Class"
        );

        // Schema Registry Group
        final String schemaGroup = "Schema Registry";
        int schemaGroupOrder = 0;

        configDef.define(
                SCHEMA_REGISTRY_URL_CONFIG,
                Type.STRING,
                null,
                Importance.MEDIUM,
                SCHEMA_REGISTRY_URL_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.LONG,
                "Schema Registry URL"
        );

        configDef.define(
                VALUE_SCHEMA_ID_CONFIG,
                Type.INT,
                null,
                Importance.MEDIUM,
                VALUE_SCHEMA_ID_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.SHORT,
                "Value Schema ID"
        );

        configDef.define(
                KEY_SCHEMA_ID_CONFIG,
                Type.INT,
                null,
                Importance.LOW,
                KEY_SCHEMA_ID_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.SHORT,
                "Key Schema ID"
        );

        configDef.define(
                SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_CONFIG,
                Type.STRING,
                SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_DEFAULT,
                Importance.LOW,
                SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.MEDIUM,
                "Schema Registry Auth Source"
        );

        configDef.define(
                SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_CONFIG,
                Type.PASSWORD,
                null,
                Importance.LOW,
                SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.LONG,
                "Schema Registry User Info"
        );

        configDef.define(
                SCHEMA_REGISTRY_SSL_TRUSTSTORE_LOCATION_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                SCHEMA_REGISTRY_SSL_TRUSTSTORE_LOCATION_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.LONG,
                "Schema Registry SSL Truststore Location"
        );

        configDef.define(
                SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_CONFIG,
                Type.PASSWORD,
                null,
                Importance.LOW,
                SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.MEDIUM,
                "Schema Registry SSL Truststore Password"
        );

        configDef.define(
                SCHEMA_AUTO_REGISTER_CONFIG,
                Type.BOOLEAN,
                SCHEMA_AUTO_REGISTER_DEFAULT,
                Importance.LOW,
                SCHEMA_AUTO_REGISTER_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.SHORT,
                "Auto Register Schema"
        );

        configDef.define(
                SCHEMA_USE_LATEST_VERSION_CONFIG,
                Type.BOOLEAN,
                SCHEMA_USE_LATEST_VERSION_DEFAULT,
                Importance.LOW,
                SCHEMA_USE_LATEST_VERSION_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.SHORT,
                "Use Latest Schema Version"
        );

        configDef.define(
                SCHEMA_SUBJECT_NAME_STRATEGY_CONFIG,
                Type.STRING,
                SCHEMA_SUBJECT_NAME_STRATEGY_DEFAULT,
                Importance.LOW,
                SCHEMA_SUBJECT_NAME_STRATEGY_DOC,
                schemaGroup,
                ++schemaGroupOrder,
                Width.LONG,
                "Subject Name Strategy"
        );

        // Message Decompression Group
        final String decompressionGroup = "Message Decompression";
        int decompressionGroupOrder = 0;

        configDef.define(
                MESSAGE_DECOMPRESSION_ENABLED_CONFIG,
                Type.BOOLEAN,
                MESSAGE_DECOMPRESSION_ENABLED_DEFAULT,
                Importance.MEDIUM,
                MESSAGE_DECOMPRESSION_ENABLED_DOC,
                decompressionGroup,
                ++decompressionGroupOrder,
                Width.SHORT,
                "Decompression Enabled"
        );

        configDef.define(
                MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_CONFIG,
                Type.STRING,
                MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_DEFAULT,
                Importance.MEDIUM,
                MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_DOC,
                decompressionGroup,
                ++decompressionGroupOrder,
                Width.LONG,
                "Delegate Converter Class"
        );

        configDef.define(
                MESSAGE_DECOMPRESSION_FIELD_PATH_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                MESSAGE_DECOMPRESSION_FIELD_PATH_DOC,
                decompressionGroup,
                ++decompressionGroupOrder,
                Width.MEDIUM,
                "Field Path to Decompress"
        );

        configDef.define(
                MESSAGE_DECOMPRESSION_FORMAT_CONFIG,
                Type.STRING,
                MESSAGE_DECOMPRESSION_FORMAT_DEFAULT,
                Importance.LOW,
                MESSAGE_DECOMPRESSION_FORMAT_DOC,
                decompressionGroup,
                ++decompressionGroupOrder,
                Width.SHORT,
                "Compression Format"
        );

        configDef.define(
                MESSAGE_DECOMPRESSION_BASE64_DECODE_CONFIG,
                Type.BOOLEAN,
                MESSAGE_DECOMPRESSION_BASE64_DECODE_DEFAULT,
                Importance.LOW,
                MESSAGE_DECOMPRESSION_BASE64_DECODE_DOC,
                decompressionGroup,
                ++decompressionGroupOrder,
                Width.SHORT,
                "Base64 Decode Enabled"
        );

        // Message Claim Check Pattern Group
        final String claimCheckGroup = "Message Claim Check Pattern";
        int claimCheckGroupOrder = 0;

        configDef.define(
                MESSAGE_CLAIMCHECK_ENABLED_CONFIG,
                Type.BOOLEAN,
                MESSAGE_CLAIMCHECK_ENABLED_DEFAULT,
                Importance.MEDIUM,
                MESSAGE_CLAIMCHECK_ENABLED_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.SHORT,
                "Claim Check Enabled"
        );

        configDef.define(
                MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_CONFIG,
                Type.STRING,
                MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_DEFAULT,
                Importance.MEDIUM,
                MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.LONG,
                "Delegate Converter Class"
        );

        configDef.define(
                MESSAGE_CLAIMCHECK_FIELD_PATH_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                MESSAGE_CLAIMCHECK_FIELD_PATH_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.MEDIUM,
                "Field Path with S3 URI"
        );

        configDef.define(
                MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_CONFIG,
                Type.BOOLEAN,
                MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_DEFAULT,
                Importance.LOW,
                MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.SHORT,
                "Decompress After Retrieval"
        );

        configDef.define(
                MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_CONFIG,
                Type.STRING,
                MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_DEFAULT,
                Importance.LOW,
                MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.SHORT,
                "Compression Format"
        );

        configDef.define(
                MESSAGE_CLAIMCHECK_BASE64_DECODE_CONFIG,
                Type.BOOLEAN,
                MESSAGE_CLAIMCHECK_BASE64_DECODE_DEFAULT,
                Importance.LOW,
                MESSAGE_CLAIMCHECK_BASE64_DECODE_DOC,
                claimCheckGroup,
                ++claimCheckGroupOrder,
                Width.SHORT,
                "Base64 Decode Enabled"
        );

        // Output Field Extraction Group
        final String outputGroup = "Output Field Extraction";
        int outputGroupOrder = 0;

        configDef.define(
                MESSAGE_OUTPUT_FIELD_EXTRACT_CONFIG,
                Type.STRING,
                null,
                Importance.LOW,
                MESSAGE_OUTPUT_FIELD_EXTRACT_DOC,
                outputGroup,
                ++outputGroupOrder,
                Width.LONG,
                "Output Field Path"
        );

        configDef.define(
                MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_CONFIG,
                Type.BOOLEAN,
                MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_DEFAULT,
                Importance.LOW,
                MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_DOC,
                outputGroup,
                ++outputGroupOrder,
                Width.SHORT,
                "Fail on Missing Field"
        );

        return configDef;
    }

    public SqsSourceConnectorConfig(Map<String, String> props) {
        super(CONFIG_DEF, props);
        validate();
    }

    private void validate() {
        // Validate SCRAM configuration
        String saslMechanism = getString(SASL_MECHANISM_CONFIG);
        if (saslMechanism != null &&
            !saslMechanism.equals("SCRAM-SHA-512") &&
            !saslMechanism.equals("SCRAM-SHA-256")) {
            throw new ConfigException(
                SASL_MECHANISM_CONFIG,
                saslMechanism,
                "SASL mechanism must be SCRAM-SHA-512 or SCRAM-SHA-256"
            );
        }

        // Validate queue URL(s) - at least one must be provided
        String queueUrl = getString(SQS_QUEUE_URL_CONFIG);
        String queueUrls = getString(SQS_QUEUE_URLS_CONFIG);

        boolean hasSingleUrl = queueUrl != null && !queueUrl.trim().isEmpty();
        boolean hasMultipleUrls = queueUrls != null && !queueUrls.trim().isEmpty();

        if (!hasSingleUrl && !hasMultipleUrls) {
            throw new ConfigException(SQS_QUEUE_URL_CONFIG, queueUrl,
                "Either sqs.queue.url or sqs.queue.urls must be provided");
        }

        // Validate multiple queue URLs format if provided
        if (hasMultipleUrls) {
            List<String> urls = getQueueUrls();
            if (urls.isEmpty()) {
                throw new ConfigException(SQS_QUEUE_URLS_CONFIG, queueUrls,
                    "sqs.queue.urls must contain at least one valid URL");
            }
            for (String url : urls) {
                if (!url.startsWith("https://sqs.") && !url.startsWith("http://")) {
                    throw new ConfigException(SQS_QUEUE_URLS_CONFIG, queueUrls,
                        "Invalid SQS queue URL format: " + url);
                }
            }
        }

        // Validate message filter policy JSON if provided
        String filterPolicy = getString(SQS_MESSAGE_FILTER_POLICY_CONFIG);
        if (filterPolicy != null && !filterPolicy.trim().isEmpty()) {
            if (!filterPolicy.trim().startsWith("{") || !filterPolicy.trim().endsWith("}")) {
                throw new ConfigException(SQS_MESSAGE_FILTER_POLICY_CONFIG, filterPolicy,
                    "Filter policy must be valid JSON object");
            }
        }

        // Validate topic
        String topic = getString(KAFKA_TOPIC_CONFIG);
        if (topic == null || topic.trim().isEmpty()) {
            throw new ConfigException(KAFKA_TOPIC_CONFIG, topic, "Kafka topic is required");
        }
    }

    public String getAwsRegion() {
        return getString(AWS_REGION_CONFIG);
    }

    public String getAwsAccessKeyId() {
        return getString(AWS_ACCESS_KEY_ID_CONFIG);
    }

    public String getAwsSecretAccessKey() {
        return getPassword(AWS_SECRET_ACCESS_KEY_CONFIG) != null
            ? getPassword(AWS_SECRET_ACCESS_KEY_CONFIG).value()
            : null;
    }

    public String getAwsAssumeRoleArn() {
        return getString(AWS_ASSUME_ROLE_ARN_CONFIG);
    }

    public String getAwsStsRoleSessionName() {
        return getString(AWS_STS_ROLE_SESSION_NAME_CONFIG);
    }

    public String getAwsStsRoleExternalId() {
        return getString(AWS_STS_ROLE_EXTERNAL_ID_CONFIG);
    }

    public String getAwsCredentialsProfile() {
        return getString(AWS_CREDENTIALS_PROFILE_CONFIG);
    }

    public String getAwsCredentialsFilePath() {
        return getString(AWS_CREDENTIALS_FILE_PATH_CONFIG);
    }

    public String getAwsEndpointOverride() {
        return getString(AWS_ENDPOINT_OVERRIDE_CONFIG);
    }

    public String getSqsQueueUrl() {
        return getString(SQS_QUEUE_URL_CONFIG);
    }

    /**
     * Gets the list of SQS queue URLs configured for multi-queue mode.
     * If sqs.queue.urls is not set but sqs.queue.url is set, returns a single-element list.
     * This provides backward compatibility with single-queue configuration.
     * <p>
     * Duplicate URLs are automatically deduplicated with a warning. Duplicating queue URLs
     * does not increase throughput - use tasks.max > 1 for parallel processing instead.
     *
     * @return List of unique queue URLs, never null or empty
     */
    public List<String> getQueueUrls() {
        String queueUrls = getString(SQS_QUEUE_URLS_CONFIG);
        if (queueUrls != null && !queueUrls.trim().isEmpty()) {
            List<String> urls = Arrays.stream(queueUrls.split(","))
                    .map(String::trim)
                    .filter(url -> !url.isEmpty())
                    .collect(Collectors.toList());

            // Deduplicate URLs using LinkedHashSet to preserve order
            Set<String> uniqueUrls = new LinkedHashSet<>(urls);
            if (uniqueUrls.size() < urls.size()) {
                int duplicates = urls.size() - uniqueUrls.size();
                log.warn(
                    "Detected {} duplicate queue URL(s) in configuration. "
                    + "Duplicating URLs does not increase throughput - "
                    + "SQS distributes messages atomically across pollers. "
                    + "Original count: {}, unique count: {}. "
                    + "To scale processing on a single queue, use tasks.max > 1. "
                    + "See documentation for throughput scaling guidance.",
                    duplicates, urls.size(), uniqueUrls.size()
                );
            }

            return new ArrayList<>(uniqueUrls);
        }

        // Fallback to single queue URL for backward compatibility
        String singleUrl = getString(SQS_QUEUE_URL_CONFIG);
        if (singleUrl != null && !singleUrl.trim().isEmpty()) {
            List<String> urls = new ArrayList<>();
            urls.add(singleUrl.trim());
            return urls;
        }

        return new ArrayList<>();
    }

    /**
     * Gets the list of message attribute names to filter/retrieve.
     * Returns empty list if not configured, meaning all attributes will be retrieved.
     *
     * @return List of attribute names to retrieve, or empty list for all
     */
    public List<String> getMessageAttributeFilterNames() {
        String filterNames = getString(SQS_MESSAGE_ATTRIBUTE_FILTER_NAMES_CONFIG);
        if (filterNames != null && !filterNames.trim().isEmpty()) {
            return Arrays.stream(filterNames.split(","))
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    /**
     * Gets the message filter policy JSON string.
     *
     * @return Filter policy JSON or null if not configured
     */
    public String getMessageFilterPolicy() {
        return getString(SQS_MESSAGE_FILTER_POLICY_CONFIG);
    }

    public int getSqsMaxMessages() {
        return getInt(SQS_MAX_MESSAGES_CONFIG);
    }

    public int getSqsWaitTimeSeconds() {
        return getInt(SQS_WAIT_TIME_SECONDS_CONFIG);
    }

    public int getSqsVisibilityTimeoutSeconds() {
        return getInt(SQS_VISIBILITY_TIMEOUT_SECONDS_CONFIG);
    }

    public boolean isSqsMessageAttributesEnabled() {
        return getBoolean(SQS_MESSAGE_ATTRIBUTES_ENABLED_CONFIG);
    }

    public boolean isSqsDeleteMessages() {
        return getBoolean(SQS_DELETE_MESSAGES_CONFIG);
    }

    public String getKafkaTopic() {
        return getString(KAFKA_TOPIC_CONFIG);
    }

    public Integer getKafkaTopicPartition() {
        return getInt(KAFKA_TOPIC_PARTITION_CONFIG);
    }

    public String getSaslMechanism() {
        return getString(SASL_MECHANISM_CONFIG);
    }

    public String getSaslJaasConfig() {
        return getPassword(SASL_JAAS_CONFIG) != null
            ? getPassword(SASL_JAAS_CONFIG).value()
            : null;
    }

    public String getSecurityProtocol() {
        return getString(SECURITY_PROTOCOL_CONFIG);
    }

    public String getDlqTopic() {
        return getString(DLQ_TOPIC_CONFIG);
    }

    public int getMaxRetries() {
        return getInt(MAX_RETRIES_CONFIG);
    }

    public long getRetryBackoffMs() {
        return getLong(RETRY_BACKOFF_MS_CONFIG);
    }

    public boolean isSqsFifoQueueEnabled() {
        return getBoolean(SQS_FIFO_QUEUE_CONFIG);
    }

    public boolean isSqsFifoAutoDetectEnabled() {
        return getBoolean(SQS_FIFO_AUTO_DETECT_CONFIG);
    }

    public boolean isSqsFifoDeduplicationEnabled() {
        return getBoolean(SQS_FIFO_DEDUPLICATION_ENABLED_CONFIG);
    }

    public long getSqsFifoDeduplicationWindowMs() {
        return getLong(SQS_FIFO_DEDUPLICATION_WINDOW_MS_CONFIG);
    }

    /**
     * Determines if the queue is a FIFO queue based on configuration or auto-detection.
     * Auto-detection checks if queue URL ends with .fifo suffix.
     * In multi-queue mode, checks the first queue URL (assumes all queues are of same type).
     *
     * @return true if the queue is a FIFO queue
     */
    public boolean isFifoQueue() {
        // If explicitly enabled, return true
        if (isSqsFifoQueueEnabled()) {
            return true;
        }

        // If auto-detect is enabled, check the queue URL
        if (isSqsFifoAutoDetectEnabled()) {
            // Check single queue URL first
            String queueUrl = getSqsQueueUrl();
            if (queueUrl != null && queueUrl.endsWith(".fifo")) {
                return true;
            }

            // Check multi-queue URLs
            List<String> urls = getQueueUrls();
            if (!urls.isEmpty()) {
                // In multi-queue mode, all queues should be same type
                // Check first queue as representative
                return urls.get(0).endsWith(".fifo");
            }
        }

        return false;
    }

    public long getPollIntervalMs() {
        return getLong(POLL_INTERVAL_MS_CONFIG);
    }

    public String getMessageConverterClass() {
        return getString(MESSAGE_CONVERTER_CLASS_CONFIG);
    }

    // Schema Registry getters
    public String getSchemaRegistryUrl() {
        return getString(SCHEMA_REGISTRY_URL_CONFIG);
    }

    public Integer getValueSchemaId() {
        return getInt(VALUE_SCHEMA_ID_CONFIG);
    }

    public Integer getKeySchemaId() {
        return getInt(KEY_SCHEMA_ID_CONFIG);
    }

    public String getSchemaRegistryBasicAuthCredentialsSource() {
        return getString(SCHEMA_REGISTRY_BASIC_AUTH_CREDENTIALS_SOURCE_CONFIG);
    }

    public String getSchemaRegistryBasicAuthUserInfo() {
        return getPassword(SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_CONFIG) != null
            ? getPassword(SCHEMA_REGISTRY_BASIC_AUTH_USER_INFO_CONFIG).value()
            : null;
    }

    public String getSchemaRegistrySslTruststoreLocation() {
        return getString(SCHEMA_REGISTRY_SSL_TRUSTSTORE_LOCATION_CONFIG);
    }

    public String getSchemaRegistrySslTruststorePassword() {
        return getPassword(SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_CONFIG) != null
            ? getPassword(SCHEMA_REGISTRY_SSL_TRUSTSTORE_PASSWORD_CONFIG).value()
            : null;
    }

    public boolean isSchemaAutoRegister() {
        return getBoolean(SCHEMA_AUTO_REGISTER_CONFIG);
    }

    public boolean isSchemaUseLatestVersion() {
        return getBoolean(SCHEMA_USE_LATEST_VERSION_CONFIG);
    }

    public String getSchemaSubjectNameStrategy() {
        return getString(SCHEMA_SUBJECT_NAME_STRATEGY_CONFIG);
    }

    // Message Decompression getters
    public boolean isMessageDecompressionEnabled() {
        return getBoolean(MESSAGE_DECOMPRESSION_ENABLED_CONFIG);
    }

    public String getMessageDecompressionDelegateConverterClass() {
        return getString(MESSAGE_DECOMPRESSION_DELEGATE_CONVERTER_CLASS_CONFIG);
    }

    public String getMessageDecompressionFieldPath() {
        return getString(MESSAGE_DECOMPRESSION_FIELD_PATH_CONFIG);
    }

    public String getMessageDecompressionFormat() {
        return getString(MESSAGE_DECOMPRESSION_FORMAT_CONFIG);
    }

    public boolean isMessageDecompressionBase64DecodeEnabled() {
        return getBoolean(MESSAGE_DECOMPRESSION_BASE64_DECODE_CONFIG);
    }

    // Claim Check Pattern Getters
    public boolean isMessageClaimCheckEnabled() {
        return getBoolean(MESSAGE_CLAIMCHECK_ENABLED_CONFIG);
    }

    public String getMessageClaimCheckDelegateConverterClass() {
        return getString(MESSAGE_CLAIMCHECK_DELEGATE_CONVERTER_CLASS_CONFIG);
    }

    public String getMessageClaimCheckFieldPath() {
        return getString(MESSAGE_CLAIMCHECK_FIELD_PATH_CONFIG);
    }

    public boolean isMessageClaimCheckDecompressEnabled() {
        return getBoolean(MESSAGE_CLAIMCHECK_DECOMPRESS_ENABLED_CONFIG);
    }

    public String getMessageClaimCheckCompressionFormat() {
        return getString(MESSAGE_CLAIMCHECK_COMPRESSION_FORMAT_CONFIG);
    }

    public boolean isMessageClaimCheckBase64DecodeEnabled() {
        return getBoolean(MESSAGE_CLAIMCHECK_BASE64_DECODE_CONFIG);
    }

    // Output Field Extraction getters
    public String getMessageOutputFieldExtract() {
        return getString(MESSAGE_OUTPUT_FIELD_EXTRACT_CONFIG);
    }

    public boolean isMessageOutputFieldExtractFailOnMissing() {
        return getBoolean(MESSAGE_OUTPUT_FIELD_EXTRACT_FAIL_ON_MISSING_CONFIG);
    }
}

