package io.connect.sqs.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.CreateQueueResponse;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/**
 * Integration tests for SQS Source Connector.
 * These tests use Testcontainers to spin up LocalStack (for SQS) and Kafka.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class SqsSourceConnectorIT {

    private static final Logger log = LoggerFactory.getLogger(SqsSourceConnectorIT.class);

    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:latest"))
            .withServices(SQS);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @Test
    void shouldCreateQueueInLocalStack() {
        SqsClient sqsClient = createSqsClient();

        CreateQueueResponse response = sqsClient.createQueue(
                CreateQueueRequest.builder()
                        .queueName("test-queue")
                        .build());

        assertThat(response.queueUrl()).isNotNull();
        assertThat(response.queueUrl()).contains("test-queue");

        log.info("Created queue: {}", response.queueUrl());
    }

    @Test
    void shouldSendMessageToQueue() {
        SqsClient sqsClient = createSqsClient();

        CreateQueueResponse createResponse = sqsClient.createQueue(
                CreateQueueRequest.builder()
                        .queueName("test-send-queue")
                        .build());

        String queueUrl = createResponse.queueUrl();

        sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody("Test message")
                .build());

        log.info("Sent message to queue: {}", queueUrl);
    }

    @Test
    void kafkaContainerShouldBeRunning() {
        assertThat(kafka.isRunning()).isTrue();
        log.info("Kafka bootstrap servers: {}", kafka.getBootstrapServers());
    }

    // Note: Full end-to-end tests are implemented in SqsToKafkaE2EIT.java
    // which validates the complete flow: SQS → Connector → Kafka

    private SqsClient createSqsClient() {
        return SqsClient.builder()
                .endpointOverride(localstack.getEndpointOverride(SQS))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                localstack.getAccessKey(),
                                localstack.getSecretKey())))
                .region(Region.of(localstack.getRegion()))
                .build();
    }
}
