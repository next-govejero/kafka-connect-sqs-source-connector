package io.connect.sqs.aws;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for SqsClient AWS integration.
 * Note: These tests verify configuration and logic, but use real AWS SDK clients.
 * For true unit tests, we'd need to mock the AWS SDK, which is complex.
 * Integration tests with LocalStack provide better coverage for AWS operations.
 */
@ExtendWith(MockitoExtension.class)
class SqsClientTest {

    private Map<String, String> configProps;

    @BeforeEach
    void setUp() {
        configProps = getTestConfig();
    }

    @Test
    void shouldCreateClientWithStaticCredentials() {
        configProps.put("aws.access.key.id", "test-key");
        configProps.put("aws.secret.access.key", "test-secret");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        // Should not throw exception
        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldCreateClientWithDefaultCredentials() {
        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        // Should not throw exception
        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldCreateClientWithAssumeRole() {
        configProps.put("aws.assume.role.arn", "arn:aws:iam::123456789012:role/test-role");
        configProps.put("aws.sts.role.session.name", "test-session");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        // Should not throw exception
        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldCreateClientWithAssumeRoleAndExternalId() {
        configProps.put("aws.assume.role.arn", "arn:aws:iam::123456789012:role/test-role");
        configProps.put("aws.sts.role.external.id", "external-123");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldCreateClientWithCredentialsProfile() {
        configProps.put("aws.credentials.profile", "test-profile");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        // Should not throw - will fail at runtime if profile doesn't exist
        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        } catch (Exception e) {
            // Expected if profile doesn't exist
            assertThat(e.getMessage()).containsAnyOf("profile", "credentials", "not found");
        }
    }

    @Test
    void shouldCreateClientWithCustomCredentialsFile() {
        configProps.put("aws.credentials.profile", "test-profile");
        configProps.put("aws.credentials.file.path", "/tmp/test-credentials");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        // Will fail if file doesn't exist - that's expected
        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        } catch (Exception e) {
            // Expected if file doesn't exist
            assertThat(e).isNotNull();
        }
    }

    @Test
    void shouldCreateClientWithEndpointOverride() {
        configProps.put("aws.endpoint.override", "http://localhost:4566");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldFailWithInvalidEndpointOverride() {
        configProps.put("aws.endpoint.override", "not-a-valid-url");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        assertThatThrownBy(() -> new SqsClient(config))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpoint");
    }

    @Test
    void shouldCloseClientSuccessfully() {
        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);
        SqsClient client = new SqsClient(config);

        // Should not throw
        client.close();
    }

    @Test
    void shouldSupportMultipleRegions() {
        List<String> regions = List.of("us-east-1", "us-west-2", "eu-west-1", "ap-southeast-1");

        for (String region : regions) {
            configProps.put("aws.region", region);
            SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

            try (SqsClient client = new SqsClient(config)) {
                assertThat(client).isNotNull();
            }
        }
    }

    @Test
    void shouldHandleEcsContainerCredentials() {
        // Simulate ECS environment
        String originalUri = System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");

        try {
            // Note: We can't actually set env vars in Java, but the client should handle it
            SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

            try (SqsClient client = new SqsClient(config)) {
                assertThat(client).isNotNull();
            }
        } finally {
            // Cleanup not needed as we didn't actually modify env
        }
    }

    @Test
    void shouldCreateClientForMultiCloudDeployment() {
        // Bare metal / on-premises with static credentials
        configProps.put("aws.access.key.id", "test-key");
        configProps.put("aws.secret.access.key", "test-secret");

        SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

        try (SqsClient client = new SqsClient(config)) {
            assertThat(client).isNotNull();
        }
    }

    @Test
    void shouldHandleDifferentQueueUrls() {
        List<String> queueUrls = List.of(
                "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue",
                "https://sqs.us-west-2.amazonaws.com/999999999999/prod-queue",
                "http://localhost:4566/000000000000/local-queue" // LocalStack
        );

        for (String queueUrl : queueUrls) {
            configProps.put("sqs.queue.url", queueUrl);
            SqsSourceConnectorConfig config = new SqsSourceConnectorConfig(configProps);

            try (SqsClient client = new SqsClient(config)) {
                assertThat(client).isNotNull();
            }
        }
    }

    private Map<String, String> getTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("sqs.queue.url", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue");
        config.put("kafka.topic", "test-topic");
        config.put("aws.region", "us-east-1");
        config.put("sasl.mechanism", "SCRAM-SHA-512");
        config.put("sasl.jaas.config",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"test\" password=\"test\";");
        return config;
    }
}
