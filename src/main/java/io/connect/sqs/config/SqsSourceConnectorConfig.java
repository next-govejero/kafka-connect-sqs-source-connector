package io.connect.sqs.config;

import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.apache.kafka.common.config.ConfigException;

import java.util.Map;

/**
 * Configuration for the SQS Source Connector.
 */
public class SqsSourceConnectorConfig extends AbstractConfig {

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

    // SQS Configuration
    public static final String SQS_QUEUE_URL_CONFIG = "sqs.queue.url";
    private static final String SQS_QUEUE_URL_DOC = "AWS SQS queue URL to consume messages from";

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

    // Polling Configuration
    public static final String POLL_INTERVAL_MS_CONFIG = "poll.interval.ms";
    private static final String POLL_INTERVAL_MS_DOC = "Interval in milliseconds between SQS polls";
    private static final long POLL_INTERVAL_MS_DEFAULT = 1000L;

    // Message Format Configuration
    public static final String MESSAGE_CONVERTER_CLASS_CONFIG = "message.converter.class";
    private static final String MESSAGE_CONVERTER_CLASS_DOC = "Class for converting SQS messages to Kafka records";
    private static final String MESSAGE_CONVERTER_CLASS_DEFAULT = "io.connect.sqs.converter.DefaultMessageConverter";

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

        // SQS Group
        final String sqsGroup = "SQS";
        int sqsGroupOrder = 0;

        configDef.define(
                SQS_QUEUE_URL_CONFIG,
                Type.STRING,
                Importance.HIGH,
                SQS_QUEUE_URL_DOC,
                sqsGroup,
                ++sqsGroupOrder,
                Width.LONG,
                "SQS Queue URL"
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

        // Validate queue URL
        String queueUrl = getString(SQS_QUEUE_URL_CONFIG);
        if (queueUrl == null || queueUrl.trim().isEmpty()) {
            throw new ConfigException(SQS_QUEUE_URL_CONFIG, queueUrl, "SQS queue URL is required");
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

    public String getSqsQueueUrl() {
        return getString(SQS_QUEUE_URL_CONFIG);
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

    public long getPollIntervalMs() {
        return getLong(POLL_INTERVAL_MS_CONFIG);
    }

    public String getMessageConverterClass() {
        return getString(MESSAGE_CONVERTER_CLASS_CONFIG);
    }
}
