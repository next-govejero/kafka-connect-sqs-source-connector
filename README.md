# Kafka Connect SQS Source Connector

[![Build Status](https://github.com/next-govejero/kafka-connect-sqs-source-connector/workflows/CI/badge.svg)](https://github.com/next-govejero/kafka-connect-sqs-source-connector/actions)
[![codecov](https://codecov.io/gh/next-govejero/kafka-connect-sqs-source-connector/branch/main/graph/badge.svg)](https://codecov.io/gh/next-govejero/kafka-connect-sqs-source-connector)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A production-ready Kafka Connect source connector that streams messages from AWS SQS queues into Apache Kafka topics. Designed for reliable, scalable, and configurable ingestion of SQS messages into Kafka-based data pipelines.

**Test Coverage: ~85%+** with comprehensive unit and integration tests.

## Features

- **Reliable Message Processing**: Long polling support with configurable visibility timeouts
- **Flexible AWS Authentication**: Support for access keys, IAM roles, and STS assume role
- **SCRAM/SHA-512 Authentication**: Mandatory secure authentication for Kafka connections
- **Message Batching**: Configurable batch sizes (1-10 messages) for efficient processing
- **Dead Letter Queue**: Route failed messages to a separate Kafka topic with full error details (exception class, message, stacktrace) in headers
- **Message Attributes**: Preserve SQS message attributes as Kafka record headers
- **Configurable Deletion**: Optional automatic deletion of processed messages from SQS
- **Metrics & Monitoring**: Built-in metrics and comprehensive logging
- **Docker Support**: Complete Docker Compose setup for local development and testing
- **Comprehensive Testing**: 85%+ test coverage with unit, integration, and end-to-end tests

## Architecture

```
┌─────────────┐          ┌──────────────────┐          ┌───────────────┐
│   AWS SQS   │ ────────>│  Kafka Connect   │ ────────>│  Kafka Topic  │
│   Queue     │  Polling │  SQS Connector   │ Produce  │               │
└─────────────┘          └──────────────────┘          └───────────────┘
                                   │
                                   │ Failed Messages
                                   ▼
                         ┌─────────────────┐
                         │  DLQ Topic      │
                         └─────────────────┘
```

## Prerequisites

- Java 11 or higher
- Apache Kafka 2.8.0 or higher
- Maven 3.6.0 or higher
- AWS SQS queue
- AWS credentials with SQS read/delete permissions

## Quick Start

### 1. Build the Connector

```bash
mvn clean package
```

This creates a distribution package at `target/kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip`.

### 2. Install the Connector

Extract the package to your Kafka Connect plugin directory:

```bash
unzip target/kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip -d /usr/local/share/kafka/plugins/
```

### 3. Configure the Connector

Create a connector configuration file (see [Configuration](#configuration) for all options):

```properties
name=sqs-source-connector
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1

# AWS Configuration
aws.region=us-east-1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/your-queue-name

# Kafka Configuration
kafka.topic=sqs-messages

# SCRAM Authentication (MANDATORY)
sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="your-username" \
  password="your-password";
```

### 4. Start the Connector

Using Kafka Connect REST API:

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @config/sqs-source-connector.properties
```

Or using standalone mode:

```bash
connect-standalone.sh config/sqs-source-connector-standalone.properties \
  config/sqs-source-connector.properties
```

## Configuration

### AWS Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `aws.region` | AWS region for SQS service | No | `us-east-1` |
| `aws.access.key.id` | AWS access key ID | No | Uses default provider chain |
| `aws.secret.access.key` | AWS secret access key | No | Uses default provider chain |
| `aws.assume.role.arn` | IAM role ARN to assume | No | - |
| `aws.sts.role.session.name` | Session name for assumed role | No | `kafka-connect-sqs` |

### SQS Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `sqs.queue.url` | SQS queue URL | **Yes** | - |
| `sqs.max.messages` | Max messages per batch (1-10) | No | `10` |
| `sqs.wait.time.seconds` | Long polling wait time (0-20) | No | `10` |
| `sqs.visibility.timeout.seconds` | Message visibility timeout | No | `30` |
| `sqs.message.attributes.enabled` | Include message attributes | No | `true` |
| `sqs.delete.messages` | Auto-delete after processing | No | `true` |

### Kafka Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `kafka.topic` | Target Kafka topic | **Yes** | - |
| `kafka.topic.partition` | Specific partition (optional) | No | - |
| `sasl.mechanism` | SASL mechanism | **Yes** | `SCRAM-SHA-512` |
| `security.protocol` | Security protocol | **Yes** | `SASL_SSL` |
| `sasl.jaas.config` | JAAS configuration | **Yes** | - |

### Error Handling Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `dlq.topic` | Dead letter queue topic | No | - |
| `max.retries` | Max retries for failed messages | No | `3` |
| `retry.backoff.ms` | Backoff between retries (ms) | No | `1000` |

### Polling Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `poll.interval.ms` | Polling interval (ms) | No | `1000` |

### Message Format Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `message.converter.class` | Message converter class | No | `io.connect.sqs.converter.DefaultMessageConverter` |

## SCRAM Authentication

This connector **requires** SCRAM-SHA-512 or SCRAM-SHA-256 authentication for secure Kafka connections.

### Setting up SCRAM on Kafka

1. Create SCRAM credentials:

```bash
kafka-configs --bootstrap-server localhost:9092 \
  --alter \
  --add-config 'SCRAM-SHA-512=[password=your-password]' \
  --entity-type users \
  --entity-name your-username
```

2. Configure the connector with SCRAM credentials:

```properties
sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="your-username" \
  password="your-password";
```

## Local Development with Docker

### Start the Full Stack

```bash
docker-compose up -d
```

This starts:
- Zookeeper
- Kafka (with SCRAM authentication)
- Kafka Connect (with the SQS connector)
- LocalStack (mock AWS SQS)
- Kafka UI (http://localhost:8080)

### Register the Connector

```bash
chmod +x docker/register-connector.sh
./docker/register-connector.sh
```

### Monitor the Connector

Access Kafka UI at http://localhost:8080 or use the Connect REST API:

```bash
# Check connector status
curl http://localhost:8083/connectors/sqs-source-connector/status

# View connector config
curl http://localhost:8083/connectors/sqs-source-connector

# Delete connector
curl -X DELETE http://localhost:8083/connectors/sqs-source-connector
```

### Consume Messages from Kafka

```bash
kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic sqs-messages \
  --from-beginning \
  --consumer-property security.protocol=SASL_PLAINTEXT \
  --consumer-property sasl.mechanism=SCRAM-SHA-512 \
  --consumer-property sasl.jaas.config='org.apache.kafka.common.security.scram.ScramLoginModule required username="connect-user" password="connect-secret";'
```

## Testing

### Run Unit Tests

```bash
mvn test
```

### Run Integration Tests

```bash
export RUN_INTEGRATION_TESTS=true
mvn verify
```

Integration tests use Testcontainers to spin up LocalStack (SQS) and Kafka.

## Message Format

The connector converts SQS messages to Kafka records with the following format:

- **Key**: SQS Message ID (String)
- **Value**: SQS Message Body (String)
- **Headers**: SQS metadata and attributes

### Headers Included

- `sqs.message.id`: SQS message ID
- `sqs.receipt.handle`: Receipt handle for the message
- `sqs.md5.of.body`: MD5 hash of message body
- `sqs.sent.timestamp`: When message was sent to SQS
- `sqs.approximate.receive.count`: Number of times message was received
- `sqs.message.attribute.*`: Custom message attributes (if enabled)

## Monitoring & Metrics

The connector logs key metrics:

- Messages received from SQS
- Messages sent to Kafka
- Messages deleted from SQS
- Failed messages
- Pending messages

Enable DEBUG logging for detailed information:

```properties
log4j.logger.io.connect.sqs=DEBUG
```

## Error Handling

The connector provides multiple error handling strategies:

1. **Retry Logic**: Configurable retries with exponential backoff
2. **Dead Letter Queue**: Route failed messages to a separate topic
3. **Visibility Timeout**: Failed messages become visible again after timeout
4. **Metrics & Logging**: Track failures for monitoring

## Performance Tuning

### Increase Throughput

```properties
sqs.max.messages=10
sqs.wait.time.seconds=20
poll.interval.ms=100
```

### Reduce Latency

```properties
sqs.wait.time.seconds=1
poll.interval.ms=100
```

### Handle Large Messages

Adjust visibility timeout to allow processing time:

```properties
sqs.visibility.timeout.seconds=300
```

## AWS Permissions

The connector requires the following SQS permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes",
        "sqs:GetQueueUrl"
      ],
      "Resource": "arn:aws:sqs:*:*:your-queue-name"
    }
  ]
}
```

If using IAM role assumption:

```json
{
  "Effect": "Allow",
  "Action": "sts:AssumeRole",
  "Resource": "arn:aws:iam::123456789012:role/your-role"
}
```

## Troubleshooting

### Connector fails to start

- Verify AWS credentials and permissions
- Check SQS queue URL is correct
- Ensure Kafka topic exists
- Validate SCRAM credentials

### Messages not appearing in Kafka

- Check connector status: `curl http://localhost:8083/connectors/sqs-source-connector/status`
- Verify messages exist in SQS queue
- Check connector logs for errors
- Ensure visibility timeout is appropriate

### High message latency

- Reduce `poll.interval.ms`
- Increase `sqs.max.messages`
- Adjust `sqs.wait.time.seconds`

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on contributing to this project.

## Versioning

This project follows [Semantic Versioning](https://semver.org/):

- **MAJOR**: Breaking changes
- **MINOR**: New features (backward compatible)
- **PATCH**: Bug fixes (backward compatible)

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## References

- [Apache Kafka Connect](https://kafka.apache.org/documentation/#connect)
- [AWS SQS Documentation](https://docs.aws.amazon.com/sqs/)
- [SCRAM Authentication](https://docs.confluent.io/platform/current/kafka/authentication_sasl/authentication_sasl_scram.html)

## Support

For issues and questions:
- [GitHub Issues](https://github.com/next-govejero/kafka-connect-sqs-source-connector/issues)
- [Discussions](https://github.com/next-govejero/kafka-connect-sqs-source-connector/discussions)

## Acknowledgments

This connector was developed following best practices from:
- [Lenses Stream Reactor](https://github.com/lensesio/stream-reactor)
- [Confluent Kafka Connect Storage Cloud](https://github.com/confluentinc/kafka-connect-storage-cloud)
- [Kafka Connect Quickstart](https://github.com/rueedlinger/kafka-connect-quickstart)
