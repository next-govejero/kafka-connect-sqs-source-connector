package io.connect.sqs.aws;

import io.connect.sqs.config.SqsSourceConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;

/**
 * AWS S3 client wrapper for retrieving claim check pattern messages.
 * Supports multiple authentication methods for flexible deployment across AWS, other clouds, and bare metal.
 */
public class S3Client implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(S3Client.class);
    private static final int BUFFER_SIZE = 8192;

    private final SqsSourceConnectorConfig config;
    private final software.amazon.awssdk.services.s3.S3Client awsS3Client;

    public S3Client(SqsSourceConnectorConfig config) {
        this.config = config;

        AwsCredentialsProvider credentialsProvider = createCredentialsProvider();
        Region region = Region.of(config.getAwsRegion());

        software.amazon.awssdk.services.s3.S3ClientBuilder builder =
                software.amazon.awssdk.services.s3.S3Client.builder()
                        .region(region)
                        .credentialsProvider(credentialsProvider);

        // Override endpoint if specified (useful for LocalStack, MinIO, or custom endpoints)
        String endpointOverride = config.getAwsEndpointOverride();
        if (endpointOverride != null && !endpointOverride.trim().isEmpty()) {
            try {
                builder.endpointOverride(java.net.URI.create(endpointOverride));
                log.info("Using custom S3 endpoint: {}", endpointOverride);
            } catch (Exception e) {
                log.error("Invalid endpoint override URL: {}", endpointOverride, e);
                throw new IllegalArgumentException("Invalid AWS endpoint override: " + endpointOverride, e);
            }
        }

        this.awsS3Client = builder.build();
        log.info("S3 Client initialized for region: {}", region);
    }

    /**
     * Retrieve object from S3 bucket.
     *
     * @param bucket S3 bucket name
     * @param key S3 object key
     * @return byte array of the object content
     * @throws IOException if there's an error reading the object
     * @throws S3Exception if there's an S3-specific error
     */
    public byte[] getObject(String bucket, String key) throws IOException {
        log.debug("Retrieving S3 object: s3://{}/{}", bucket, key);

        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        try (ResponseInputStream<GetObjectResponse> response = awsS3Client.getObject(getObjectRequest);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = response.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            byte[] result = outputStream.toByteArray();
            log.debug("Successfully retrieved {} bytes from s3://{}/{}", result.length, bucket, key);
            return result;

        } catch (S3Exception e) {
            log.error("S3 error retrieving object s3://{}/{}: {} - {}",
                    bucket, key, e.statusCode(), e.awsErrorDetails().errorMessage());
            throw e;
        } catch (IOException e) {
            log.error("IO error reading S3 object s3://{}/{}", bucket, key, e);
            throw e;
        }
    }

    /**
     * Retrieve object from S3 using a full S3 URI (s3://bucket/key).
     *
     * @param s3Uri S3 URI in the format s3://bucket/key
     * @return byte array of the object content
     * @throws IOException if there's an error reading the object
     * @throws IllegalArgumentException if the S3 URI is invalid
     */
    public byte[] getObjectByUri(String s3Uri) throws IOException {
        if (s3Uri == null || !s3Uri.startsWith("s3://")) {
            throw new IllegalArgumentException("Invalid S3 URI: " + s3Uri + ". Must start with 's3://'");
        }

        String path = s3Uri.substring(5); // Remove "s3://"
        int firstSlash = path.indexOf('/');

        if (firstSlash == -1 || firstSlash == 0 || firstSlash == path.length() - 1) {
            throw new IllegalArgumentException("Invalid S3 URI format: " + s3Uri +
                    ". Expected format: s3://bucket/key");
        }

        String bucket = path.substring(0, firstSlash);
        String key = path.substring(firstSlash + 1);

        return getObject(bucket, key);
    }

    /**
     * Creates AWS credentials provider based on configuration.
     * Supports static credentials, profile-based credentials, assume role, and default provider chain.
     */
    private AwsCredentialsProvider createCredentialsProvider() {
        String accessKeyId = config.getAwsAccessKeyId();
        String secretKey = config.getAwsSecretAccessKey();
        String roleArn = config.getAwsAssumeRoleArn();
        String profileName = config.getAwsProfileName();
        String profilePath = config.getAwsProfilePath();

        // Priority order:
        // 1. STS Assume Role (requires base credentials from static or default provider)
        // 2. Static credentials (access key + secret key)
        // 3. Named profile
        // 4. Default credentials provider chain

        AwsCredentialsProvider baseProvider;

        if (accessKeyId != null && !accessKeyId.trim().isEmpty()
                && secretKey != null && !secretKey.trim().isEmpty()) {
            // Use static credentials
            log.info("Using static AWS credentials with access key: {}...", accessKeyId.substring(0, Math.min(4, accessKeyId.length())));
            baseProvider = StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretKey)
            );
        } else if (profileName != null && !profileName.trim().isEmpty()) {
            // Use named profile
            log.info("Using AWS profile: {}", profileName);
            ProfileCredentialsProvider.Builder profileBuilder = ProfileCredentialsProvider.builder()
                    .profileName(profileName);

            if (profilePath != null && !profilePath.trim().isEmpty()) {
                profileBuilder.profileFile(ProfileFile.builder()
                        .content(Paths.get(profilePath))
                        .type(ProfileFile.Type.CREDENTIALS)
                        .build());
            }

            baseProvider = profileBuilder.build();
        } else {
            // Use default credentials provider chain (environment variables, system properties,
            // EC2 instance profile, ECS task role, EKS ServiceAccount)
            log.info("Using AWS Default Credentials Provider Chain (environment variables, instance profile, etc.)");
            baseProvider = DefaultCredentialsProvider.create();
        }

        // If assume role is configured, wrap the base provider
        if (roleArn != null && !roleArn.trim().isEmpty()) {
            log.info("Assuming AWS role: {}", roleArn);

            try (StsClient stsClient = StsClient.builder()
                    .region(Region.of(config.getAwsRegion()))
                    .credentialsProvider(baseProvider)
                    .build()) {

                String sessionName = config.getAwsAssumeRoleSessionName() != null
                        ? config.getAwsAssumeRoleSessionName()
                        : "kafka-connect-sqs-s3-session";

                AssumeRoleRequest.Builder roleRequestBuilder = AssumeRoleRequest.builder()
                        .roleArn(roleArn)
                        .roleSessionName(sessionName);

                String externalId = config.getAwsAssumeRoleExternalId();
                if (externalId != null && !externalId.trim().isEmpty()) {
                    roleRequestBuilder.externalId(externalId);
                }

                return StsAssumeRoleCredentialsProvider.builder()
                        .stsClient(stsClient)
                        .refreshRequest(roleRequestBuilder.build())
                        .build();
            }
        }

        return baseProvider;
    }

    @Override
    public void close() {
        if (awsS3Client != null) {
            awsS3Client.close();
            log.info("S3 Client closed");
        }
    }
}
