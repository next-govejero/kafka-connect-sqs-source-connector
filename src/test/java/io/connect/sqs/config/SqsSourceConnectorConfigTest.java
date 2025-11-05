package io.connect.sqs.config;

import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqsSourceConnectorConfigTest {

    @Test
    void shouldCreateConfigWithRequiredParameters() {
        Map<String, String> props = getMinimalConfig();

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getSqsQueueUrl()).isEqualTo("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        assertThat(config.getKafkaTopic()).isEqualTo("test-topic");
        assertThat(config.getAwsRegion()).isEqualTo("us-east-1");
    }

    @Test
    void shouldUseDefaultValues() {
        Map<String, String> props = getMinimalConfig();

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getSqsMaxMessages()).isEqualTo(10);
        assertThat(config.getSqsWaitTimeSeconds()).isEqualTo(10);
        assertThat(config.getSqsVisibilityTimeoutSeconds()).isEqualTo(30);
        assertThat(config.isSqsMessageAttributesEnabled()).isTrue();
        assertThat(config.isSqsDeleteMessages()).isTrue();
        assertThat(config.getSaslMechanism()).isEqualTo("SCRAM-SHA-512");
        assertThat(config.getSecurityProtocol()).isEqualTo("SASL_SSL");
    }

    @Test
    void shouldFailWhenQueueUrlIsMissing() {
        Map<String, String> props = getMinimalConfig();
        props.remove(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG);

        assertThatThrownBy(() -> new SqsSourceConnectorConfig(props))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void shouldFailWhenTopicIsMissing() {
        Map<String, String> props = getMinimalConfig();
        props.remove(SqsSourceConnectorConfig.KAFKA_TOPIC_CONFIG);

        assertThatThrownBy(() -> new SqsSourceConnectorConfig(props))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void shouldFailWhenSaslMechanismIsInvalid() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "PLAIN");

        assertThatThrownBy(() -> new SqsSourceConnectorConfig(props))
                .isInstanceOf(ConfigException.class)
                .hasMessageContaining("SASL mechanism must be SCRAM-SHA-512 or SCRAM-SHA-256");
    }

    @Test
    void shouldAcceptScramSha256() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "SCRAM-SHA-256");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getSaslMechanism()).isEqualTo("SCRAM-SHA-256");
    }

    @Test
    void shouldValidateMaxMessagesRange() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.SQS_MAX_MESSAGES_CONFIG, "15");

        assertThatThrownBy(() -> new SqsSourceConnectorConfig(props))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void shouldValidateWaitTimeRange() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.SQS_WAIT_TIME_SECONDS_CONFIG, "25");

        assertThatThrownBy(() -> new SqsSourceConnectorConfig(props))
                .isInstanceOf(ConfigException.class);
    }

    @Test
    void shouldConfigureAwsCredentials() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.AWS_ACCESS_KEY_ID_CONFIG, "test-key-id");
        props.put(SqsSourceConnectorConfig.AWS_SECRET_ACCESS_KEY_CONFIG, "test-secret-key");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getAwsAccessKeyId()).isEqualTo("test-key-id");
        assertThat(config.getAwsSecretAccessKey()).isEqualTo("test-secret-key");
    }

    @Test
    void shouldConfigureAssumeRole() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.AWS_ASSUME_ROLE_ARN_CONFIG,
                "arn:aws:iam::123456789012:role/test-role");
        props.put(SqsSourceConnectorConfig.AWS_STS_ROLE_SESSION_NAME_CONFIG, "test-session");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getAwsAssumeRoleArn()).isEqualTo("arn:aws:iam::123456789012:role/test-role");
        assertThat(config.getAwsStsRoleSessionName()).isEqualTo("test-session");
    }

    @Test
    void shouldConfigureErrorHandling() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.DLQ_TOPIC_CONFIG, "dlq-topic");
        props.put(SqsSourceConnectorConfig.MAX_RETRIES_CONFIG, "5");
        props.put(SqsSourceConnectorConfig.RETRY_BACKOFF_MS_CONFIG, "2000");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getDlqTopic()).isEqualTo("dlq-topic");
        assertThat(config.getMaxRetries()).isEqualTo(5);
        assertThat(config.getRetryBackoffMs()).isEqualTo(2000);
    }

    @Test
    void shouldConfigurePolling() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.POLL_INTERVAL_MS_CONFIG, "5000");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getPollIntervalMs()).isEqualTo(5000);
    }

    @Test
    void shouldConfigureMessageConverter() {
        Map<String, String> props = getMinimalConfig();
        props.put(SqsSourceConnectorConfig.MESSAGE_CONVERTER_CLASS_CONFIG,
                "com.example.CustomConverter");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        assertThat(config.getMessageConverterClass()).isEqualTo("com.example.CustomConverter");
    }

    private Map<String, String> getMinimalConfig() {
        Map<String, String> props = new HashMap<>();
        props.put(SqsSourceConnectorConfig.SQS_QUEUE_URL_CONFIG,
                "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        props.put(SqsSourceConnectorConfig.KAFKA_TOPIC_CONFIG, "test-topic");
        props.put(SqsSourceConnectorConfig.AWS_REGION_CONFIG, "us-east-1");
        props.put(SqsSourceConnectorConfig.SASL_MECHANISM_CONFIG, "SCRAM-SHA-512");
        props.put(SqsSourceConnectorConfig.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return props;
    }
}
