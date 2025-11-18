# Kafka Connect SQS Source Connector

[![Build Status](https://github.com/next-govejero/kafka-connect-sqs-source-connector/workflows/CI/badge.svg)](https://github.com/next-govejero/kafka-connect-sqs-source-connector/actions)
[![codecov](https://codecov.io/gh/next-govejero/kafka-connect-sqs-source-connector/branch/main/graph/badge.svg)](https://codecov.io/gh/next-govejero/kafka-connect-sqs-source-connector)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

A production-ready Kafka Connect source connector that streams messages from AWS SQS queues into Apache Kafka topics. Designed for reliable, scalable, and configurable ingestion of SQS messages into Kafka-based data pipelines.

**Test Coverage: ~85%+** with comprehensive unit and integration tests.

## Features

- **Schema Registry Support**: Enterprise-grade schema validation with Avro, Protobuf, and JSON Schema converters
- **Message Decompression**: Automatic decompression of gzip/deflate/zlib compressed data with Base64 decoding and flexible field-path support
- **Multi-Queue Support**: Consume from multiple SQS queues with automatic task distribution for parallel processing
- **Message Filtering**: Client-side filtering with support for exact match, prefix, exists, and numeric conditions
- **Reliable Message Processing**: Long polling support with configurable visibility timeouts
- **FIFO Queue Support**: Full support for SQS FIFO queues with ordering preservation and deduplication
- **Retry with Exponential Backoff**: Active retry mechanism with jitter to prevent thundering herd
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
                         Multi-Queue Support (Parallel Processing)
                         ┌──────────────────────────────────┐
                         │                                  │
┌─────────────┐          │  ┌──────────────────┐          │  ┌───────────────┐
│   AWS SQS   │ ────────>│  │  Task 1          │ ────────>│  │  Kafka Topic  │
│   Queue 1   │  Polling │  │  (Queue 1)       │ Produce  │  │               │
└─────────────┘          │  └──────────────────┘          │  └───────────────┘
                         │                                  │
┌─────────────┐          │  ┌──────────────────┐          │  ┌───────────────┐
│   AWS SQS   │ ────────>│  │  Task 2          │ ────────>│  │  Kafka Topic  │
│   Queue 2   │  Polling │  │  (Queue 2)       │ Produce  │  │               │
└─────────────┘          │  └──────────────────┘          │  └───────────────┘
                         │          │                      │
                         │          │ Failed Messages      │
                         └──────────┼──────────────────────┘
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

The connector is automatically installed with the proper structure:
```
/usr/local/share/kafka/plugins/
└── kafka-connect-sqs-source-1.0.0-SNAPSHOT-package/
    ├── LICENSE
    ├── README.md
    ├── config/              (example configuration files)
    └── lib/                 (connector and dependency JARs)
        └── kafka-connect-sqs-source-1.0.0-SNAPSHOT.jar
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

The connector package includes example configuration files in the `config/` directory. Copy and customize one for your environment:

```bash
# Copy example config from the installed connector
cp /usr/local/share/kafka/plugins/kafka-connect-sqs-source-1.0.0-SNAPSHOT/config/sqs-source-connector.properties \
   ./my-sqs-connector.properties

# Edit with your settings (queue URL, Kafka topic, credentials, etc.)
vi ./my-sqs-connector.properties
```

Using Kafka Connect REST API:

```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @my-sqs-connector.properties
```

Or using standalone mode:

```bash
connect-standalone.sh \
  /usr/local/share/kafka/plugins/kafka-connect-sqs-source-1.0.0-SNAPSHOT/config/sqs-source-connector-standalone.properties \
  ./my-sqs-connector.properties
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
| `sqs.queue.url` | SQS queue URL (single queue mode) | **Yes*** | - |
| `sqs.queue.urls` | Comma-separated list of SQS queue URLs (multi-queue mode) | **Yes*** | - |
| `sqs.max.messages` | Max messages per batch (1-10) | No | `10` |
| `sqs.wait.time.seconds` | Long polling wait time (0-20) | No | `10` |
| `sqs.visibility.timeout.seconds` | Message visibility timeout | No | `30` |
| `sqs.message.attributes.enabled` | Include message attributes | No | `true` |
| `sqs.message.attribute.filter.names` | Comma-separated list of specific attributes to retrieve | No | All attributes |
| `sqs.message.filter.policy` | JSON filter policy for message filtering | No | - |
| `sqs.delete.messages` | Auto-delete after processing | No | `true` |

*Either `sqs.queue.url` OR `sqs.queue.urls` must be provided.

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

### FIFO Queue Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `sqs.fifo.queue` | Enable FIFO queue support | No | `false` |
| `sqs.fifo.auto.detect` | Auto-detect FIFO from queue URL (.fifo suffix) | No | `true` |
| `sqs.fifo.deduplication.enabled` | Enable deduplication tracking | No | `true` |
| `sqs.fifo.deduplication.window.ms` | Deduplication tracking window (ms) | No | `300000` |

### Polling Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `poll.interval.ms` | Polling interval (ms) | No | `1000` |

### Message Format Configuration

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `message.converter.class` | Message converter class | No | `io.connect.sqs.converter.DefaultMessageConverter` |

### Schema Registry Configuration

For enterprise schema validation, the connector supports Avro, Protobuf, and JSON Schema converters with Confluent Schema Registry integration.

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `schema.registry.url` | Schema Registry URL | Yes (for schema converters) | - |
| `value.schema.id` | Specific schema ID for values | No | Auto-inferred |
| `key.schema.id` | Specific schema ID for keys | No | String schema |
| `schema.auto.register` | Auto-register schemas | No | `true` |
| `schema.use.latest.version` | Use latest schema version | No | `false` |
| `schema.subject.name.strategy` | Subject naming strategy | No | `TopicNameStrategy` |
| `schema.registry.basic.auth.user.info` | Basic auth credentials | No | - |

**Available Message Converters:**

- `io.connect.sqs.converter.DefaultMessageConverter` - Plain string (default)
- `io.connect.sqs.converter.AvroMessageConverter` - Apache Avro with Schema Registry
- `io.connect.sqs.converter.ProtobufMessageConverter` - Protocol Buffers with Schema Registry
- `io.connect.sqs.converter.JsonSchemaMessageConverter` - JSON Schema with Schema Registry
- `io.connect.sqs.converter.DecompressingMessageConverter` - Decompression wrapper for compressed messages

**Example with Avro:**

```json
{
  "message.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
  "schema.registry.url": "http://schema-registry:8081",
  "schema.auto.register": "true"
}
```

For comprehensive Schema Registry documentation including examples, schema evolution, and best practices, see [Schema Registry Documentation](docs/SCHEMA_REGISTRY.md).

### Message Decompression Configuration

The connector supports automatic decompression of compressed message data (gzip, deflate, zlib). This is useful when SQS messages contain compressed payloads, such as EventBridge events with compressed data fields.

**Configuration:**

| Property | Description | Required | Default |
|----------|-------------|----------|---------|
| `message.decompression.enabled` | Enable message decompression | No | `false` |
| `message.decompression.delegate.converter.class` | Converter to use after decompression | No | `DefaultMessageConverter` |
| `message.decompression.field.path` | JSON field path to decompress (e.g., `detail.data`) | No | - (decompresses entire body) |
| `message.decompression.format` | Compression format: `AUTO`, `GZIP`, `DEFLATE`, `ZLIB` | No | `AUTO` |
| `message.decompression.base64.decode` | Attempt Base64 decoding before decompression | No | `true` |

**Usage:**

To use message decompression, set `message.converter.class` to `DecompressingMessageConverter` and configure the delegate converter:

```json
{
  "message.converter.class": "io.connect.sqs.converter.DecompressingMessageConverter",
  "message.decompression.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter",
  "message.decompression.field.path": "detail.data",
  "message.decompression.format": "AUTO",
  "message.decompression.base64.decode": "true"
}
```

**Example 1: Decompress Entire Message Body**

If your entire SQS message is gzip-compressed and Base64-encoded:

```json
{
  "message.converter.class": "io.connect.sqs.converter.DecompressingMessageConverter",
  "message.decompression.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter"
}
```

**Example 2: Decompress Nested Field (EventBridge Events)**

For EventBridge events where `detail.data` contains compressed JSON:

```json
{
  "version": "0",
  "id": "event-id",
  "detail-type": "PriceCache.Updated",
  "source": "PriceCacheService",
  "detail": {
    "data": "H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA=="
  }
}
```

Configuration:

```json
{
  "message.converter.class": "io.connect.sqs.converter.DecompressingMessageConverter",
  "message.decompression.delegate.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
  "message.decompression.field.path": "detail.data",
  "schema.registry.url": "http://schema-registry:8081"
}
```

**Example 3: Deeply Nested Field Paths**

For complex JSON structures with nested compressed fields:

```json
{
  "message.converter.class": "io.connect.sqs.converter.DecompressingMessageConverter",
  "message.decompression.field.path": "payload.body.compressed_content"
}
```

**Features:**

- **Auto-detection**: Automatically detects compression format (gzip, deflate, zlib) based on magic bytes
- **Base64 handling**: Automatically decodes Base64-encoded compressed data (common in JSON)
- **Flexible field paths**: Use dot notation to decompress any nested JSON field
- **Format override**: Specify exact compression format for better performance
- **Converter chaining**: Works with any message converter (Default, Avro, Protobuf, JSON Schema)
- **Graceful fallback**: Returns original data if decompression fails

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

- **Key**: SQS Message ID (or MessageGroupId for FIFO queues)
- **Value**: SQS Message Body (String)
- **Headers**: SQS metadata and attributes

### Headers Included

- `sqs.message.id`: SQS message ID
- `sqs.receipt.handle`: Receipt handle for the message
- `sqs.md5.of.body`: MD5 hash of message body
- `sqs.sent.timestamp`: When message was sent to SQS
- `sqs.approximate.receive.count`: Number of times message was received
- `sqs.message.attribute.*`: Custom message attributes (if enabled)

#### FIFO Queue Headers (when using FIFO queues)

- `sqs.message.group.id`: Message group ID for ordering
- `sqs.message.deduplication.id`: Deduplication ID
- `sqs.sequence.number`: FIFO sequence number

#### DLQ Headers (for failed messages)

- `error.class`: Exception class name
- `error.message`: Exception message
- `error.stacktrace`: Full stack trace
- `error.timestamp`: When the error occurred
- `retry.count`: Number of retry attempts made
- `retry.max`: Maximum retries configured
- `retry.exhausted`: Whether all retries were exhausted

## FIFO Queue Support

The connector fully supports AWS SQS FIFO queues with the following features:

### Ordering Preservation

FIFO queues preserve message ordering within message groups. The connector uses the `MessageGroupId` as the Kafka record key, ensuring all messages from the same group are routed to the same Kafka partition. This maintains the ordering guarantees in Kafka.

### Auto-Detection

The connector can automatically detect FIFO queues based on the `.fifo` suffix in the queue URL:

```properties
# Auto-detection (default)
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/my-queue.fifo
sqs.fifo.auto.detect=true

# Or explicit configuration
sqs.fifo.queue=true
```

### Deduplication

FIFO queues provide exactly-once processing through deduplication. The connector tracks `MessageDeduplicationId` to prevent processing duplicate messages:

```properties
sqs.fifo.deduplication.enabled=true
sqs.fifo.deduplication.window.ms=300000  # 5 minutes
```

### Example FIFO Configuration

```properties
name=sqs-fifo-source-connector
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1

# AWS Configuration
aws.region=us-east-1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/orders.fifo

# FIFO Configuration
sqs.fifo.queue=true
sqs.fifo.deduplication.enabled=true
sqs.fifo.deduplication.window.ms=300000

# Kafka Configuration
kafka.topic=orders
sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="your-username" \
  password="your-password";
```

## Multi-Queue Support

The connector supports consuming from multiple SQS queues simultaneously, with automatic task distribution for parallel processing. This is a major scalability improvement that reduces operational overhead.

### How It Works

When you configure multiple queue URLs, the connector automatically:
1. Creates one Kafka Connect task per queue
2. Distributes queues across available tasks
3. Each task processes its assigned queue independently
4. All tasks produce to the same Kafka topic

### Configuration

```properties
name=sqs-multi-queue-connector
connector.class=io.connect.sqs.SqsSourceConnector
# Important: Set tasks.max to at least the number of queues
tasks.max=3

# Multi-queue configuration
sqs.queue.urls=https://sqs.us-east-1.amazonaws.com/123456789012/orders,\
https://sqs.us-east-1.amazonaws.com/123456789012/payments,\
https://sqs.us-east-1.amazonaws.com/123456789012/notifications

# All messages go to the same topic
kafka.topic=sqs-messages

# AWS and Kafka configuration
aws.region=us-east-1
sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="your-username" \
  password="your-password";
```

### Important Notes

- **tasks.max**: Set this to at least the number of queues. If `tasks.max < number of queues`, some queues will not be processed.
- **Backward Compatibility**: You can still use `sqs.queue.url` for single queue mode.
- **Load Balancing**: Each queue gets its own task, ensuring parallel processing.
- **Same Topic**: All messages from all queues are routed to the same Kafka topic.

### Best Practices

1. **Match tasks to queues**: `tasks.max` should equal or exceed the number of queues
2. **Monitor all queues**: Each task reports metrics independently
3. **Use similar queue configurations**: All queues should have similar message volume and processing characteristics
4. **Consider FIFO constraints**: If using FIFO queues, ordering is preserved within each queue, not across queues

## Message Filtering

The connector supports client-side message filtering based on SQS message attributes. This reduces unnecessary processing by filtering out messages that don't match specific criteria.

### How It Works

1. Messages are received from SQS with their attributes
2. The filter processor evaluates each message against the filter policy
3. Messages that don't match are immediately deleted from SQS
4. Only matching messages are converted to Kafka records

### Filter Policy Syntax

The filter policy uses a JSON format similar to SNS subscription filters:

```json
{
  "attribute_name": ["value1", "value2", {"prefix": "prod-"}, {"exists": true}]
}
```

### Supported Filter Conditions

| Condition | Syntax | Description |
|-----------|--------|-------------|
| Exact Match | `["value1", "value2"]` | Matches if attribute equals any value |
| Prefix Match | `[{"prefix": "prod-"}]` | Matches if attribute starts with prefix |
| Exists | `[{"exists": true}]` | Matches if attribute exists |
| Not Exists | `[{"exists": false}]` | Matches if attribute doesn't exist |
| Numeric Comparison | `[{"numeric": [">=", 100]}]` | Numeric comparisons: `=`, `!=`, `<`, `<=`, `>`, `>=` |

### Filter Logic

- **Within attribute**: OR logic (any condition can match)
- **Between attributes**: AND logic (all attributes must match)

### Configuration Examples

**Filter by exact message type:**
```properties
sqs.message.filter.policy={"Type":["order","payment"]}
```

**Filter by environment prefix:**
```properties
sqs.message.filter.policy={"Environment":[{"prefix":"prod-"}]}
```

**Require priority attribute:**
```properties
sqs.message.filter.policy={"Priority":[{"exists":true}]}
```

**Complex filter with multiple conditions:**
```properties
sqs.message.filter.policy={"Type":["order"],"Priority":["high","urgent"],"Amount":[{"numeric":[">=",100]}]}
```

**Retrieve only specific attributes (reduces network traffic):**
```properties
sqs.message.attribute.filter.names=Type,Priority,Environment
```

### Complete Filtering Example

```properties
name=sqs-filtered-connector
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1

# AWS Configuration
aws.region=us-east-1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/events

# Message Filtering
sqs.message.attributes.enabled=true
sqs.message.attribute.filter.names=Type,Priority,Region
sqs.message.filter.policy={"Type":["order","payment"],"Priority":[{"prefix":"high"}],"Region":[{"exists":true}]}

# Kafka Configuration
kafka.topic=filtered-events
sasl.mechanism=SCRAM-SHA-512
security.protocol=SASL_SSL
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required \
  username="your-username" \
  password="your-password";
```

### Monitoring Filter Performance

The connector tracks filter statistics:
- `Messages Filtered Out`: Count of messages that didn't match the filter
- `Messages Passed Filter`: Count of messages that matched

Enable INFO logging to see these metrics:
```
SQS Source Task Metrics:
  Messages Received: 1000
  Messages Filtered Out: 750
  Messages Passed Filter: 250
```

### Best Practices

1. **Start broad, then narrow**: Begin with a permissive filter and tighten as needed
2. **Monitor filter rates**: High filter-out rates may indicate inefficient queue usage
3. **Use attribute name filtering**: Reduces network traffic by only requesting needed attributes
4. **Test filter policies**: Validate JSON syntax before deploying
5. **Consider SNS filtering**: For true server-side filtering, use SNS subscription filters before SQS

## Retry and Exponential Backoff

The connector implements an active retry mechanism with exponential backoff and jitter to improve resilience and reduce messages routed to the Dead Letter Queue.

### How It Works

When message processing fails, the connector:
1. Records the failure and calculates a backoff period
2. Schedules the message for retry after the backoff expires
3. Allows SQS visibility timeout to redeliver the message
4. Retries until `max.retries` is exhausted, then routes to DLQ

### Exponential Backoff Formula

```
backoff = baseBackoffMs * (2 ^ (attempt - 1)) * (1 ± jitter)
```

Example with `retry.backoff.ms=1000`:
- Attempt 1: ~1000ms (1s)
- Attempt 2: ~2000ms (2s)
- Attempt 3: ~4000ms (4s)

### Jitter to Prevent Thundering Herd

The connector applies 30% jitter by default to randomize retry times. This prevents multiple failed messages from retrying simultaneously, which could overwhelm downstream services.

### Configuration

```properties
# Maximum retry attempts before routing to DLQ
max.retries=3

# Base backoff time in milliseconds
retry.backoff.ms=1000
```

### Retry Headers in DLQ

When a message exhausts all retries and is routed to the DLQ, these headers are included:
- `retry.count`: Number of retry attempts made
- `retry.max`: Maximum retries configured
- `retry.exhausted`: Boolean indicating all retries were exhausted

### Example Scenario

With `max.retries=3` and `retry.backoff.ms=1000`:
1. Message fails → scheduled retry after ~1s
2. Retry 1 fails → scheduled retry after ~2s
3. Retry 2 fails → scheduled retry after ~4s
4. Retry 3 fails → routed to DLQ with `retry.count=3`, `retry.exhausted=true`

## Monitoring & Metrics

The connector logs key metrics:

- Messages received from SQS
- Messages sent to Kafka
- Messages deleted from SQS
- Failed messages
- Messages retried (when using retry mechanism)
- Messages deduplicated (when using FIFO deduplication)
- Pending messages
- Messages in retry (awaiting retry)

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

## Acknowledgments

This connector was developed following best practices from:
- [Lenses Stream Reactor](https://github.com/lensesio/stream-reactor)
- [Confluent Kafka Connect Storage Cloud](https://github.com/confluentinc/kafka-connect-storage-cloud)
- [Kafka Connect Quickstart](https://github.com/rueedlinger/kafka-connect-quickstart)
