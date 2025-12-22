package io.connect.sqs.aws;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for S3Client, specifically testing S3-specific credentials configuration.
 */
class S3ClientTest {

    private static final String TEST_REGION = "us-east-1";
    private static final String TEST_TOPIC = "test-topic";
    private static final String TEST_QUEUE = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue";

    /**
     * Test that S3-specific credentials configuration is properly read from config.
     */
    @Test
    void testS3SpecificCredentialsConfigurationLoading() {
        Map<String, String> props = new HashMap<>();
        props.put("aws.region", TEST_REGION);
        props.put("sqs.queue.url", TEST_QUEUE);
        props.put("kafka.topic", TEST_TOPIC);
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");

        // Set S3-specific credentials
        props.put("s3.assume.role.arn", "arn:aws:iam::987654321098:role/s3-reader-role");
        props.put("s3.sts.role.session.name", "custom-s3-session");
        props.put("s3.sts.role.external.id", "s3-external-id");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        // Verify S3-specific credentials are loaded
        assertEquals("arn:aws:iam::987654321098:role/s3-reader-role", config.getS3AssumeRoleArn());
        assertEquals("custom-s3-session", config.getS3StsRoleSessionName());
        assertEquals("s3-external-id", config.getS3StsRoleExternalId());
    }

    /**
     * Test that S3-specific credentials default to null when not set.
     */
    @Test
    void testS3SpecificCredentialsDefaults() {
        Map<String, String> props = new HashMap<>();
        props.put("aws.region", TEST_REGION);
        props.put("sqs.queue.url", TEST_QUEUE);
        props.put("kafka.topic", TEST_TOPIC);
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        // Verify S3-specific credentials are null (will fall back to general AWS credentials)
        assertNull(config.getS3AssumeRoleArn());
        assertEquals("kafka-connect-sqs-s3", config.getS3StsRoleSessionName()); // Has default
        assertNull(config.getS3StsRoleExternalId());
    }

    /**
     * Test that general AWS credentials can still be used without S3-specific ones.
     */
    @Test
    void testGeneralAwsCredentialsWithoutS3Specific() {
        Map<String, String> props = new HashMap<>();
        props.put("aws.region", TEST_REGION);
        props.put("sqs.queue.url", TEST_QUEUE);
        props.put("kafka.topic", TEST_TOPIC);
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");

        // Set general AWS credentials only
        props.put("aws.assume.role.arn", "arn:aws:iam::123456789012:role/general-role");
        props.put("aws.sts.role.session.name", "general-session");
        props.put("aws.sts.role.external.id", "general-external-id");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        // Verify general credentials are loaded
        assertEquals("arn:aws:iam::123456789012:role/general-role", config.getAwsAssumeRoleArn());
        assertEquals("general-session", config.getAwsStsRoleSessionName());
        assertEquals("general-external-id", config.getAwsStsRoleExternalId());

        // S3-specific should be null (will fall back)
        assertNull(config.getS3AssumeRoleArn());
        assertNull(config.getS3StsRoleExternalId());
    }

    /**
     * Test that S3-specific credentials can override general credentials.
     */
    @Test
    void testS3SpecificCredentialsOverrideGeneral() {
        Map<String, String> props = new HashMap<>();
        props.put("aws.region", TEST_REGION);
        props.put("sqs.queue.url", TEST_QUEUE);
        props.put("kafka.topic", TEST_TOPIC);
        props.put("sasl.mechanism", "SCRAM-SHA-512");
        props.put("security.protocol", "SASL_SSL");
        props.put("sasl.jaas.config", "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");

        // Set general AWS credentials
        props.put("aws.assume.role.arn", "arn:aws:iam::123456789012:role/general-role");
        props.put("aws.sts.role.session.name", "general-session");
        props.put("aws.sts.role.external.id", "general-external-id");

        // Set S3-specific credentials (should override for S3 operations)
        props.put("s3.assume.role.arn", "arn:aws:iam::987654321098:role/s3-specific-role");
        props.put("s3.sts.role.session.name", "s3-specific-session");
        props.put("s3.sts.role.external.id", "s3-specific-external-id");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(props);

        // Verify both sets of credentials are available
        assertEquals("arn:aws:iam::123456789012:role/general-role", config.getAwsAssumeRoleArn());
        assertEquals("arn:aws:iam::987654321098:role/s3-specific-role", config.getS3AssumeRoleArn());

        assertEquals("general-session", config.getAwsStsRoleSessionName());
        assertEquals("s3-specific-session", config.getS3StsRoleSessionName());

        assertEquals("general-external-id", config.getAwsStsRoleExternalId());
        assertEquals("s3-specific-external-id", config.getS3StsRoleExternalId());
    }
}
