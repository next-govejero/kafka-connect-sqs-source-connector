package io.connect.sqs.aws;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry;
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AWS SQS client wrapper for the connector.
 * Supports multiple authentication methods for flexible deployment across AWS, other clouds, and bare metal.
 */
public class SqsClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqsClient.class);

    private final SqsSourceConnectorConfig config;
    private final software.amazon.awssdk.services.sqs.SqsClient awsSqsClient;
    private final String queueUrl;

    public SqsClient(SqsSourceConnectorConfig config) {
        this.config = config;
        this.queueUrl = config.getSqsQueueUrl();

        AwsCredentialsProvider credentialsProvider = createCredentialsProvider();
        Region region = Region.of(config.getAwsRegion());

        software.amazon.awssdk.services.sqs.SqsClientBuilder builder =
                software.amazon.awssdk.services.sqs.SqsClient.builder()
                        .region(region)
                        .credentialsProvider(credentialsProvider);

        // Override endpoint if specified (useful for LocalStack, custom endpoints, or testing)
        String endpointOverride = config.getAwsEndpointOverride();
        if (endpointOverride != null && !endpointOverride.trim().isEmpty()) {
            try {
                builder.endpointOverride(java.net.URI.create(endpointOverride));
                log.info("Using custom SQS endpoint: {}", endpointOverride);
            } catch (Exception e) {
                log.error("Invalid endpoint override URL: {}", endpointOverride, e);
                throw new IllegalArgumentException("Invalid AWS endpoint override: " + endpointOverride, e);
            }
        }

        this.awsSqsClient = builder.build();

        log.info("SQS Client initialized for region: {}, queue: {}", region, queueUrl);

        // Log FIFO queue detection
        if (config.isFifoQueue()) {
            log.info("FIFO queue detected, will request FIFO-specific attributes");
        }
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
                List<String> attributeFilterNames = config.getMessageAttributeFilterNames();
                if (!attributeFilterNames.isEmpty()) {
                    // Request only specific attributes for reduced network traffic
                    requestBuilder.messageAttributeNames(attributeFilterNames);
                    log.debug("Requesting specific message attributes: {}", attributeFilterNames);
                } else {
                    // Request all attributes
                    requestBuilder.messageAttributeNames("All");
                }
            }

            // Request system attributes including FIFO-specific ones
            List<MessageSystemAttributeName> systemAttributes = new ArrayList<>(Arrays.asList(
                    MessageSystemAttributeName.SENT_TIMESTAMP,
                    MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT,
                    MessageSystemAttributeName.APPROXIMATE_FIRST_RECEIVE_TIMESTAMP
            ));

            // Add FIFO-specific attributes if it's a FIFO queue
            if (config.isFifoQueue()) {
                systemAttributes.add(MessageSystemAttributeName.MESSAGE_GROUP_ID);
                systemAttributes.add(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID);
                systemAttributes.add(MessageSystemAttributeName.SEQUENCE_NUMBER);
                log.debug("Requesting FIFO-specific system attributes");
            }

            requestBuilder.messageSystemAttributeNames(systemAttributes);

            ReceiveMessageRequest request = requestBuilder.build();
            ReceiveMessageResponse response = awsSqsClient.receiveMessage(request);

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

        DeleteMessageBatchResponse response = awsSqsClient.deleteMessageBatch(request);

        if (!response.failed().isEmpty()) {
            log.warn("Failed to delete {} messages from SQS", response.failed().size());
            response.failed().forEach(failed ->
                    log.warn("Failed to delete message {}: {} - {}",
                            failed.id(), failed.code(), failed.message()));
        }
    }

    /**
     * Creates AWS credentials provider with support for multiple authentication methods:
     * 1. Static credentials (access key + secret key)
     * 2. AWS credentials profile (from ~/.aws/credentials or custom file)
     * 3. Default credentials provider chain, which includes:
     *    - System properties
     *    - Environment variables
     *    - Web Identity Token (EKS)
     *    - Shared credentials file (~/.aws/credentials)
     *    - ECS container credentials (ECS Fargate task role)
     *    - EC2 instance profile credentials
     * 4. STS Assume Role (optional, on top of base credentials)
     *
     * This supports deployments in:
     * - AWS (ECS Fargate, EC2, EKS)
     * - Other cloud providers (Azure, GCP, etc.) using static credentials or profiles
     * - Bare metal / on-premises using static credentials or profiles
     */
    private AwsCredentialsProvider createCredentialsProvider() {
        String accessKeyId = config.getAwsAccessKeyId();
        String secretAccessKey = config.getAwsSecretAccessKey();
        String profileName = config.getAwsCredentialsProfile();
        String credentialsFilePath = config.getAwsCredentialsFilePath();
        String assumeRoleArn = config.getAwsAssumeRoleArn();

        AwsCredentialsProvider baseProvider;

        // Priority 1: Static credentials (highest priority for explicit configuration)
        if (accessKeyId != null && secretAccessKey != null) {
            log.info("Using static AWS credentials (access key + secret key)");
            log.info("Deployment: Any environment (cloud-agnostic, bare metal, on-premises)");
            baseProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        // Priority 2: Named profile from credentials file
        else if (profileName != null && !profileName.trim().isEmpty()) {
            log.info("Using AWS credentials profile: {}", profileName);
            log.info("Deployment: Any environment with credentials file (cloud-agnostic, bare metal, on-premises)");

            ProfileCredentialsProvider.Builder profileBuilder =
                    ProfileCredentialsProvider.builder().profileName(profileName);

            // Use custom credentials file path if specified
            if (credentialsFilePath != null && !credentialsFilePath.trim().isEmpty()) {
                try {
                    java.nio.file.Path credPath = java.nio.file.Paths.get(credentialsFilePath);
                    profileBuilder.profileFile(ProfileFile.builder()
                            .content(credPath)
                            .type(ProfileFile.Type.CREDENTIALS)
                            .build());
                    log.info("Using custom credentials file: {}", credentialsFilePath);
                } catch (Exception e) {
                    log.error("Failed to load credentials file: {}", credentialsFilePath, e);
                    throw new IllegalArgumentException("Invalid credentials file path: " + credentialsFilePath, e);
                }
            }

            baseProvider = profileBuilder.build();
        }
        // Priority 3: Default credentials provider chain
        else {
            log.info("Using default AWS credentials provider chain");
            log.info("Credentials will be resolved from:");
            log.info("  1. System properties (aws.accessKeyId, aws.secretAccessKey)");
            log.info("  2. Environment variables (AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)");
            log.info("  3. Web Identity Token (EKS ServiceAccount)");
            log.info("  4. Shared credentials file (~/.aws/credentials)");
            log.info("  5. ECS container credentials (Fargate task role)");
            log.info("  6. EC2 instance profile credentials");

            // Detect deployment environment
            String ecsContainerCredentialsUri = System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
            String ecsContainerCredentialsFull = System.getenv("AWS_CONTAINER_CREDENTIALS_FULL_URI");
            String ec2MetadataDisabled = System.getenv("AWS_EC2_METADATA_DISABLED");

            if (ecsContainerCredentialsUri != null || ecsContainerCredentialsFull != null) {
                log.info("Deployment: AWS ECS Fargate (detected container credentials)");
            } else if ("true".equalsIgnoreCase(ec2MetadataDisabled)) {
                log.info("Deployment: Non-AWS environment (EC2 metadata disabled)");
            } else {
                log.info("Deployment: Environment-based authentication");
            }

            baseProvider = DefaultCredentialsProvider.create();
        }

        // Optional: Assume role for cross-account access or additional permissions
        if (assumeRoleArn != null && !assumeRoleArn.trim().isEmpty()) {
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

        AssumeRoleRequest.Builder assumeRoleBuilder = AssumeRoleRequest.builder()
                .roleArn(roleArn)
                .roleSessionName(config.getAwsStsRoleSessionName());

        // Add external ID if provided (required for third-party access)
        String externalId = config.getAwsStsRoleExternalId();
        if (externalId != null && !externalId.trim().isEmpty()) {
            assumeRoleBuilder.externalId(externalId);
            log.info("Using external ID for role assumption (third-party access)");
        }

        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(stsClient)
                .refreshRequest(assumeRoleBuilder.build())
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
        if (awsSqsClient != null) {
            awsSqsClient.close();
            log.info("SQS Client closed");
        }
    }
}
