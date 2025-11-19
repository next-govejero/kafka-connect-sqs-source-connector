# Claim Check Pattern - Configuration Examples

This directory contains ready-to-use connector configuration examples for the Claim Check Pattern.

## Prerequisites

Before using these examples, ensure you have:

1. **Kafka Connect** running (standalone or distributed mode)
2. **AWS credentials** configured with permissions for:
   - SQS: `ReceiveMessage`, `DeleteMessage`, `GetQueueAttributes`
   - S3: `GetObject`
3. **Kafka cluster** with SCRAM authentication enabled
4. **Schema Registry** (only for Avro example)

## Examples

### 1. Simple S3 Reference (`claim-check-simple.json`)

**Use Case:** Entire message body is an S3 URI

**SQS Message Format:**
```
s3://my-bucket/large-messages/msg-123.json
```

**Configuration:**
```bash
# Update the configuration
vim examples/claim-check-simple.json

# Deploy to Kafka Connect
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-simple.json
```

**What to Update:**
- `sqs.queue.url`: Your SQS queue URL
- `kafka.topic`: Target Kafka topic
- `aws.region`: Your AWS region
- `kafka.bootstrap.servers`: Your Kafka brokers
- `sasl.jaas.config`: Your Kafka credentials

---

### 2. EventBridge with Nested S3 Reference (`claim-check-eventbridge.json`)

**Use Case:** EventBridge events with S3 URI in nested field

**SQS Message Format:**
```json
{
  "version": "0",
  "id": "abc123-def456",
  "detail-type": "LargeDataUpdate",
  "source": "my.application",
  "detail": {
    "s3Key": "s3://my-bucket/data/file.json",
    "metadata": {
      "size": 5242880,
      "timestamp": "2024-01-15T10:30:00Z"
    }
  }
}
```

**Configuration:**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-eventbridge.json
```

**Key Setting:**
- `message.claimcheck.field.path`: `detail.s3Key` - Specifies where to find the S3 URI

---

### 3. Compressed Data in S3 (`claim-check-compressed.json`)

**Use Case:** S3 objects contain gzip-compressed and Base64-encoded data

**SQS Message Format:**
```json
{
  "detail": {
    "s3Key": "s3://my-bucket/compressed/data.json.gz"
  }
}
```

**S3 Object Content:** Base64-encoded gzipped JSON

**Configuration:**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-compressed.json
```

**Key Settings:**
- `message.claimcheck.decompress.enabled`: `true`
- `message.claimcheck.compression.format`: `GZIP`
- `message.claimcheck.base64.decode`: `true`

---

### 4. Avro Schema Validation (`claim-check-avro.json`)

**Use Case:** Validate S3 content against Avro schema before sending to Kafka

**SQS Message Format:**
```json
{
  "detail": {
    "s3Data": "s3://my-bucket/user-events/event-123.json"
  }
}
```

**S3 Object Content:** JSON matching Avro schema
```json
{
  "userId": 12345,
  "name": "John Doe",
  "email": "john@example.com",
  "timestamp": 1705320600000
}
```

**Prerequisites:**
1. Schema Registry running at `http://schema-registry:8081`
2. Avro schema registered for subject `user-events-value`

**Configuration:**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-avro.json
```

**Key Settings:**
- `message.claimcheck.delegate.converter.class`: `AvroMessageConverter`
- `schema.registry.url`: Schema Registry URL
- `schema.subject.name`: Schema subject name

---

### 5. Decompress then Claim Check (`decompressing-claim-check.json`)

**Use Case:** Field contains GZIP+Base64 encoded content that could be either direct JSON or an S3 URI

**Problem:** EventBridge `detail.data` field contains compressed content. After decompression, it might be:
- Direct JSON data (fits in message)
- S3 URI (for large data)

**SQS Message Format:**
```json
{
  "version": "0",
  "detail-type": "PriceCache.Updated",
  "detail": {
    "data": "H4sIAAAAAAAA/+1d2XLbSJb9FQ...VERY LONG BASE64 STRING..."
  }
}
```

**After Decompression, `detail.data` contains one of:**
1. Direct JSON: `{"price":100,"currency":"USD"}`
2. S3 URI: `s3://my-bucket/large-pricing-data.json`

**Configuration:**
```bash
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/decompressing-claim-check.json
```

**Key Settings:**
- `message.converter.class`: `DecompressingClaimCheckMessageConverter`
- `message.decompression.field.path`: `detail.data`
- `message.decompression.format`: `GZIP`
- `message.decompression.base64.decode`: `true`

**How It Works:**
1. Decodes Base64 from `detail.data`
2. Decompresses GZIP
3. Checks if result is an S3 URI (`s3://...`)
4. If S3 URI: retrieves from S3
5. If direct JSON: uses it as-is
6. Replaces `detail.data` with final content

**Example Flow:**

```
Original Message:
{
  "detail": {
    "data": "H4sIAAAAAAAA/ytJLS4BAAxGw84EAAAA"  ← Base64 GZIP
  }
}

↓ Base64 Decode + GZIP Decompress

Decompressed content:
"s3://my-bucket/data.json"  ← S3 URI detected!

↓ Retrieve from S3

Final Message to Kafka:
{
  "detail": {
    "data": "{\"price\":100,\"currency\":\"USD\"}"  ← S3 content
  }
}
```

**Alternate Flow (Direct JSON):**

```
Original Message:
{
  "detail": {
    "data": "H4sIAAAAAAAA/6tWyk...ABC123"  ← Base64 GZIP
  }
}

↓ Base64 Decode + GZIP Decompress

Decompressed content:
"{\"price\":100,\"currency\":\"USD\"}"  ← Direct JSON, no S3 URI

↓ No S3 retrieval needed

Final Message to Kafka:
{
  "detail": {
    "data": "{\"price\":100,\"currency\":\"USD\"}"  ← Used as-is
  }
}
```

---

## Deployment

### Standalone Mode

```bash
# Copy connector JAR to plugins directory
cp target/kafka-connect-sqs-source-*.jar $KAFKA_HOME/plugins/

# Update example configuration
vim examples/claim-check-simple.json

# Deploy connector
curl -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-simple.json
```

### Distributed Mode

```bash
# Ensure connector is in plugin.path
# Update example configuration
vim examples/claim-check-simple.json

# Deploy to cluster
curl -X POST http://kafka-connect-1:8083/connectors \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-simple.json
```

## Monitoring

### Check Connector Status
```bash
curl http://localhost:8083/connectors/sqs-source-claim-check-simple/status
```

### View Connector Tasks
```bash
curl http://localhost:8083/connectors/sqs-source-claim-check-simple/tasks
```

### Check Logs
```bash
# Standalone mode
tail -f $KAFKA_HOME/logs/connect.log

# Distributed mode (Docker)
docker logs -f kafka-connect
```

## Testing

### 1. Upload Test Data to S3
```bash
echo '{"message": "Hello from S3!"}' > test-message.json
aws s3 cp test-message.json s3://my-bucket/test/message-1.json
```

### 2. Send S3 Reference to SQS
```bash
# Simple mode
aws sqs send-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/123456789012/my-queue \
  --message-body 's3://my-bucket/test/message-1.json'

# EventBridge mode
aws sqs send-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/123456789012/my-queue \
  --message-body '{"detail":{"s3Key":"s3://my-bucket/test/message-1.json"}}'
```

### 3. Verify in Kafka
```bash
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic large-messages \
  --from-beginning \
  --property print.key=true \
  --property print.headers=true
```

## Troubleshooting

### Connector Fails to Start
```bash
# Check connector configuration
curl http://localhost:8083/connectors/sqs-source-claim-check-simple/config

# Validate configuration
curl -X PUT http://localhost:8083/connector-plugins/io.connect.sqs.SqsSourceConnector/config/validate \
  -H "Content-Type: application/json" \
  -d @examples/claim-check-simple.json
```

### S3 Access Denied
```bash
# Test AWS credentials
aws sts get-caller-identity

# Test S3 access
aws s3 ls s3://my-bucket/

# Check IAM permissions
aws iam get-user-policy --user-name your-user --policy-name your-policy
```

### Messages Not Appearing in Kafka
```bash
# Check connector lag
curl http://localhost:8083/connectors/sqs-source-claim-check-simple/status

# Check SQS queue
aws sqs get-queue-attributes \
  --queue-url https://sqs.us-east-1.amazonaws.com/123456789012/my-queue \
  --attribute-names ApproximateNumberOfMessages

# Check DLQ (if configured)
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic sqs-errors \
  --from-beginning
```

## AWS IAM Policy Example

Minimal IAM policy required for claim check pattern:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:us-east-1:123456789012:my-queue"
    },
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::my-bucket/*"
    }
  ]
}
```

## Additional Resources

- [Claim Check Pattern Documentation](../docs/CLAIM_CHECK_PATTERN.md)
- [Main README](../README.md)
- [Schema Registry Documentation](../docs/SCHEMA_REGISTRY.md)
- [Message Decompression Documentation](../docs/MESSAGE_DECOMPRESSION.md)
