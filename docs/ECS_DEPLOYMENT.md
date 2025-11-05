# Deploying Kafka Connect SQS Connector on AWS ECS Fargate

This guide explains how to deploy the SQS Source Connector on AWS ECS Fargate with proper IAM role authentication.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                        AWS Cloud                             │
│                                                              │
│  ┌──────────────┐       ┌─────────────────┐                │
│  │  SQS Queue   │──────>│  ECS Fargate    │                │
│  │              │       │  ┌───────────┐  │                │
│  └──────────────┘       │  │ Kafka     │  │                │
│                         │  │ Connect   │  │                │
│                         │  │ + SQS     │  │                │
│                         │  │ Connector │  │                │
│                         │  └───────────┘  │                │
│                         │        │        │                │
│                         │   (Task Role)   │                │
│                         └────────┼────────┘                │
│                                  │                          │
│                                  ▼                          │
│                         ┌─────────────────┐                │
│                         │  Kafka Cluster  │                │
│                         │  (MSK or self)  │                │
│                         └─────────────────┘                │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

1. **AWS Account** with appropriate permissions
2. **ECS Cluster** (Fargate or EC2 launch type)
3. **Kafka Cluster** accessible from ECS (VPC configuration)
4. **SQS Queue** with messages to consume
5. **ECR Repository** for the connector Docker image

## Step 1: Create IAM Task Role

The ECS task role provides AWS credentials to the connector without needing to configure access keys.

### Create IAM Policy for SQS Access

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SQSConnectorPermissions",
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl",
        "sqs:ChangeMessageVisibility"
      ],
      "Resource": "arn:aws:sqs:us-east-1:123456789012:your-queue-name"
    }
  ]
}
```

### Create IAM Role

```bash
# Create the policy
aws iam create-policy \
  --policy-name KafkaConnectSqsPolicy \
  --policy-document file://sqs-policy.json

# Create the role with ECS trust relationship
cat > trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

aws iam create-role \
  --role-name KafkaConnectSqsTaskRole \
  --assume-role-policy-document file://trust-policy.json

# Attach the policy to the role
aws iam attach-role-policy \
  --role-name KafkaConnectSqsTaskRole \
  --policy-arn arn:aws:iam::123456789012:policy/KafkaConnectSqsPolicy
```

## Step 2: Build and Push Docker Image

```bash
# Build the connector image
docker build -t kafka-connect-sqs:latest .

# Tag for ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

docker tag kafka-connect-sqs:latest \
  123456789012.dkr.ecr.us-east-1.amazonaws.com/kafka-connect-sqs:latest

# Push to ECR
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/kafka-connect-sqs:latest
```

## Step 3: Create ECS Task Definition

```json
{
  "family": "kafka-connect-sqs",
  "taskRoleArn": "arn:aws:iam::123456789012:role/KafkaConnectSqsTaskRole",
  "executionRoleArn": "arn:aws:iam::123456789012:role/ecsTaskExecutionRole",
  "networkMode": "awsvpc",
  "requiresCompatibilities": ["FARGATE"],
  "cpu": "1024",
  "memory": "2048",
  "containerDefinitions": [
    {
      "name": "kafka-connect",
      "image": "123456789012.dkr.ecr.us-east-1.amazonaws.com/kafka-connect-sqs:latest",
      "essential": true,
      "portMappings": [
        {
          "containerPort": 8083,
          "protocol": "tcp"
        }
      ],
      "environment": [
        {
          "name": "CONNECT_BOOTSTRAP_SERVERS",
          "value": "b-1.msk-cluster.kafka.us-east-1.amazonaws.com:9096"
        },
        {
          "name": "CONNECT_GROUP_ID",
          "value": "sqs-connector-group"
        },
        {
          "name": "CONNECT_CONFIG_STORAGE_TOPIC",
          "value": "sqs-connector-configs"
        },
        {
          "name": "CONNECT_OFFSET_STORAGE_TOPIC",
          "value": "sqs-connector-offsets"
        },
        {
          "name": "CONNECT_STATUS_STORAGE_TOPIC",
          "value": "sqs-connector-status"
        },
        {
          "name": "CONNECT_REST_ADVERTISED_HOST_NAME",
          "value": "connect"
        },
        {
          "name": "CONNECT_KEY_CONVERTER",
          "value": "org.apache.kafka.connect.storage.StringConverter"
        },
        {
          "name": "CONNECT_VALUE_CONVERTER",
          "value": "org.apache.kafka.connect.storage.StringConverter"
        }
      ],
      "secrets": [
        {
          "name": "KAFKA_USERNAME",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:kafka/connect/username"
        },
        {
          "name": "KAFKA_PASSWORD",
          "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:kafka/connect/password"
        }
      ],
      "logConfiguration": {
        "logDriver": "awslogs",
        "options": {
          "awslogs-group": "/ecs/kafka-connect-sqs",
          "awslogs-region": "us-east-1",
          "awslogs-stream-prefix": "ecs"
        }
      }
    }
  ]
}
```

Register the task definition:

```bash
aws ecs register-task-definition \
  --cli-input-json file://task-definition.json
```

## Step 4: Create ECS Service

```bash
aws ecs create-service \
  --cluster your-ecs-cluster \
  --service-name kafka-connect-sqs \
  --task-definition kafka-connect-sqs:1 \
  --desired-count 1 \
  --launch-type FARGATE \
  --network-configuration "awsvpcConfiguration={subnets=[subnet-12345,subnet-67890],securityGroups=[sg-12345],assignPublicIp=DISABLED}"
```

## Step 5: Deploy Connector Configuration

Once the ECS task is running, deploy the connector configuration:

```bash
# Get the task's private IP or use an ALB if configured
CONNECT_URL="http://10.0.1.100:8083"

# Register the connector
curl -X POST ${CONNECT_URL}/connectors \
  -H "Content-Type: application/json" \
  -d @config/sqs-source-connector-ecs.json
```

Connector configuration (sqs-source-connector-ecs.json):

```json
{
  "name": "sqs-source-connector-ecs",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",
    "aws.region": "us-east-1",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789012/your-queue-name",
    "sqs.max.messages": "10",
    "sqs.wait.time.seconds": "10",
    "sqs.visibility.timeout.seconds": "30",
    "sqs.message.attributes.enabled": "true",
    "sqs.delete.messages": "true",
    "kafka.topic": "sqs-messages",
    "sasl.mechanism": "SCRAM-SHA-512",
    "security.protocol": "SASL_SSL",
    "sasl.jaas.config": "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${env:KAFKA_USERNAME}\" password=\"${env:KAFKA_PASSWORD}\";",
    "dlq.topic": "sqs-dlq",
    "max.retries": "3",
    "retry.backoff.ms": "1000"
  }
}
```

## Monitoring

### CloudWatch Logs

The connector logs are sent to CloudWatch Logs:

```bash
aws logs tail /ecs/kafka-connect-sqs --follow
```

### Connector Status

Check connector and task status:

```bash
# Connector status
curl ${CONNECT_URL}/connectors/sqs-source-connector-ecs/status | jq '.'

# Task status
curl ${CONNECT_URL}/connectors/sqs-source-connector-ecs/tasks | jq '.'
```

### CloudWatch Metrics

Create CloudWatch alarms for:
- ECS task CPU/memory usage
- SQS queue metrics (messages available, in-flight)
- Kafka Connect metrics (via JMX exporter)

## Scaling

### Vertical Scaling

Adjust CPU and memory in the task definition:

```bash
aws ecs update-service \
  --cluster your-ecs-cluster \
  --service kafka-connect-sqs \
  --task-definition kafka-connect-sqs:2
```

### Horizontal Scaling

Currently, the connector supports single task per queue. For multiple queues:

1. Deploy separate connector instances for each queue
2. Or modify `tasks.max` and implement queue distribution (future enhancement)

## Cross-Account SQS Access

If your SQS queue is in a different AWS account:

```json
{
  "name": "sqs-source-connector-cross-account",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "aws.region": "us-east-1",
    "aws.assume.role.arn": "arn:aws:iam::999999999999:role/CrossAccountSqsRole",
    "aws.sts.role.session.name": "kafka-connect-cross-account",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/999999999999/cross-account-queue",
    "kafka.topic": "sqs-messages"
  }
}
```

The task role must have permission to assume the cross-account role.

## Using AWS MSK with IAM Authentication

If using Amazon MSK with IAM authentication instead of SCRAM:

1. Add MSK permissions to task role:

```json
{
  "Effect": "Allow",
  "Action": [
    "kafka-cluster:Connect",
    "kafka-cluster:AlterCluster",
    "kafka-cluster:DescribeCluster",
    "kafka-cluster:WriteData",
    "kafka-cluster:ReadData"
  ],
  "Resource": "arn:aws:kafka:us-east-1:123456789012:cluster/msk-cluster/*"
}
```

2. Update connector configuration:

```json
{
  "security.protocol": "SASL_SSL",
  "sasl.mechanism": "AWS_MSK_IAM",
  "sasl.jaas.config": "software.amazon.msk.auth.iam.IAMLoginModule required;",
  "sasl.client.callback.handler.class": "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
}
```

## Troubleshooting

### Connector Fails to Start

1. Check CloudWatch logs for authentication errors
2. Verify task role has SQS permissions
3. Ensure VPC/security groups allow connectivity

### No Credentials Error

```
Unable to load credentials from any of the providers in the chain
```

**Solution**: Verify task role is attached and has correct trust relationship.

### Connection Timeout

**Solution**: Check VPC configuration and security groups allow outbound connections to SQS and Kafka.

## Best Practices

1. **Use AWS Secrets Manager** for Kafka credentials
2. **Enable VPC endpoints** for SQS to avoid NAT gateway costs
3. **Set up CloudWatch alarms** for monitoring
4. **Use Application Load Balancer** for accessing Connect REST API
5. **Enable task auto-scaling** based on SQS queue depth
6. **Use separate IAM roles** for different environments (dev/staging/prod)

## Cost Optimization

1. Use Fargate Spot for non-critical workloads
2. Enable VPC endpoints to reduce data transfer costs
3. Right-size task CPU/memory based on actual usage
4. Consider EC2 launch type for consistent workloads

## Security Considerations

1. Never expose Connect REST API publicly
2. Use VPC security groups to restrict access
3. Rotate credentials regularly
4. Enable encryption in transit (TLS for Kafka, HTTPS for SQS)
5. Use least-privilege IAM policies
6. Enable CloudTrail for audit logging
