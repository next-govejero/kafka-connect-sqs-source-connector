package io.connect.sqs.aws;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS SQS client wrapper for the connector.
 */
public class SqsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqsClient.class);

    private final SqsSourceConnectorConfig config;
    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsClient(SqsSourceConnectorConfig config) {
        this.config = config;
        this.queueUrl = config.getSqsQueueUrl();

        AwsCredentialsProvider credentialsProvider = createCredentialsProvider();
        Region region = Region.of(config.getAwsRegion());

        this.sqsClient = software.amazon.awssdk.services.sqs.SqsClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        log.info("SQS Client initialized for region: {}, queue: {}", region, queueUrl);
    }

    /**
     * Receive messages from SQS queue.
     */
    public List<Message> receiveMessages() {
        try {
            ReceiveMessageRequest.Builder requestBuilder = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(config.getSqsMaxMessages())
                    .waitTimeSeconds(config.getSqsWaitTimeSeconds())
                    .visibilityTimeout(config.getSqsVisibilityTimeoutSeconds());

            // Include message attributes if enabled
            if (config.isSqsMessageAttributesEnabled()) {
                requestBuilder.messageAttributeNames("All");
            }

            ReceiveMessageRequest request = requestBuilder.build();
            ReceiveMessageResponse response = sqsClient.receiveMessage(request);

            List<Message> messages = response.messages();
            log.debug("Received {} messages from SQS queue: {}", messages.size(), queueUrl);

            return messages;

        } catch (Exception e) {
            log.error("Error receiving messages from SQS", e);
            throw new RuntimeException("Failed to receive messages from SQS", e);
        }
    }

    /**
     * Delete messages from SQS queue.
     */
    public void deleteMessages(List<Message> messages) {
        if (messages.isEmpty()) {
            return;
        }

        try {
            // SQS batch delete supports up to 10 messages at a time
            List<List<Message>> batches = partitionList(messages, 10);

            for (List<Message> batch : batches) {
                deleteMessageBatch(batch);
            }

            log.debug("Deleted {} messages from SQS queue: {}", messages.size(), queueUrl);

        } catch (Exception e) {
            log.error("Error deleting messages from SQS", e);
            throw new RuntimeException("Failed to delete messages from SQS", e);
        }
    }

    private void deleteMessageBatch(List<Message> messages) {
        List<DeleteMessageBatchRequestEntry> entries = messages.stream()
                .map(msg -> DeleteMessageBatchRequestEntry.builder()
                        .id(msg.messageId())
                        .receiptHandle(msg.receiptHandle())
                        .build())
                .collect(Collectors.toList());

        DeleteMessageBatchRequest request = DeleteMessageBatchRequest.builder()
                .queueUrl(queueUrl)
                .entries(entries)
                .build();

        DeleteMessageBatchResponse response = sqsClient.deleteMessageBatch(request);

        if (!response.failed().isEmpty()) {
            log.warn("Failed to delete {} messages from SQS", response.failed().size());
            response.failed().forEach(failed ->
                    log.warn("Failed to delete message {}: {} - {}",
                            failed.id(), failed.code(), failed.message()));
        }
    }

    private AwsCredentialsProvider createCredentialsProvider() {
        String accessKeyId = config.getAwsAccessKeyId();
        String secretAccessKey = config.getAwsSecretAccessKey();
        String assumeRoleArn = config.getAwsAssumeRoleArn();

        AwsCredentialsProvider baseProvider;

        if (accessKeyId != null && secretAccessKey != null) {
            log.info("Using static AWS credentials");
            baseProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        } else {
            log.info("Using default AWS credentials provider chain");
            baseProvider = DefaultCredentialsProvider.create();
        }

        if (assumeRoleArn != null) {
            log.info("Assuming IAM role: {}", assumeRoleArn);
            return createAssumeRoleProvider(baseProvider, assumeRoleArn);
        }

        return baseProvider;
    }

    private AwsCredentialsProvider createAssumeRoleProvider(
            AwsCredentialsProvider baseProvider,
            String roleArn) {

        StsClient stsClient = StsClient.builder()
                .region(Region.of(config.getAwsRegion()))
                .credentialsProvider(baseProvider)
                .build();

        AssumeRoleRequest assumeRoleRequest = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(config.getAwsStsRoleSessionName())
                .build();

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(assumeRoleRequest)
                .build();
    }

    private <T> List<List<T>> partitionList(List<T> list, int partitionSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += partitionSize) {
            partitions.add(list.subList(i, Math.min(i + partitionSize, list.size())));
        }
        return partitions;
    }

    @Override
    public void close() {
        if (sqsClient != null) {
            sqsClient.close();
            log.info("SQS Client closed");
        }
    }
}
