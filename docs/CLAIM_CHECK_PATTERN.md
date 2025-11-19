# Claim Check Pattern

The Kafka Connect SQS Source Connector implements the **Claim Check Pattern** to handle messages that exceed SQS and EventBridge size limits. Instead of sending large payloads directly, systems send an S3 URI reference which the connector retrieves and processes automatically.

This feature is essential for:

- **Large EventBridge events** exceeding the 256KB limit
- **Bulk data processing** where message payloads are stored in S3
- **File upload notifications** that reference S3 objects
- **Archive retrieval** and processing of historical data
- **Cost optimization** - S3 storage is cheaper than keeping large messages in SQS

## Table of Contents

- [Overview](#overview)
- [How It Works](#how-it-works)
- [Configuration](#configuration)
- [Usage Examples](#usage-examples)
- [Advanced Use Cases](#advanced-use-cases)
- [Performance Considerations](#performance-considerations)
- [Troubleshooting](#troubleshooting)
- [Best Practices](#best-practices)

## Overview

The **Claim Check Pattern** is an enterprise integration pattern where:

1. The **producer** stores large data in S3 and sends a small reference (S3 URI) via SQS/EventBridge
2. The **connector** retrieves the actual data from S3 using the URI
3. The **data** is processed and sent to Kafka as if it came directly from SQS

### Why Use the Claim Check Pattern?

**Size Limits:**
- SQS standard/FIFO: 256KB per message
- EventBridge: 256KB per event
- S3 objects: Up to 5TB

**Cost Optimization:**
- SQS: $0.40 per million requests + data transfer
- S3: $0.023 per GB storage (standard tier)
- Large messages stored in S3 are more cost-effective

**Data Retention:**
- SQS: Maximum 14 days retention
- S3: Indefinite retention with lifecycle policies
- Better for audit trails and compliance

### Architecture

```
Producer Side:
┌─────────────────┐
│  Large Payload  │
│     (5 MB)      │
└────────┬────────┘
         │
         ▼
    ┌────────┐
    │   S3   │ ◄── Store payload
    └────┬───┘
         │
         ▼
┌─────────────────────────┐
│  SQS/EventBridge Event  │
│  {                      │
│    "s3Key":             │
│    "s3://bucket/key"    │
│  }                      │
└────────┬────────────────┘
         │
         ▼

Connector Side:
┌──────────────────────────┐
│  SQS Source Connector    │
│                          │
│  ┌────────────────────┐  │
│  │ Receive from SQS   │  │
│  └────────┬───────────┘  │
│           │              │
│           ▼              │
│  ┌────────────────────┐  │
│  │ ClaimCheck         │  │
│  │ MessageConverter   │  │
│  │                    │  │
│  │ 1. Detect S3 URI   │  │
│  │ 2. Retrieve from   │  │
│  │    S3 (5 MB)       │  │
│  │ 3. Optional        │  │
│  │    decompression   │  │
│  └────────┬───────────┘  │
│           │              │
│           ▼              │
│  ┌────────────────────┐  │
│  │ Delegate Converter │  │
│  │ (Avro/Protobuf/    │  │
│  │  Default)          │  │
│  └────────┬───────────┘  │
└───────────┼──────────────┘
            │
            ▼
    ┌───────────────┐
    │  Kafka Topic  │
    └───────────────┘
```

## How It Works

### 1. S3 URI Detection

The `ClaimCheckMessageConverter` detects S3 URIs in two modes:

**Entire Body Mode** (default):
```json
"s3://my-bucket/large-messages/msg-123.json"
```

**Field Path Mode**:
```json
{
  "version": "0",
  "detail-type": "LargeDataUpdate",
  "detail": {
    "s3Key": "s3://my-bucket/data/file.json",
    "metadata": {
      "size": 5242880,
      "timestamp": "2024-01-15T10:30:00Z"
    }
  }
}
```

### 2. S3 Retrieval

The connector:
1. Parses the S3 URI to extract bucket and key
2. Uses the same AWS credentials configured for SQS access
3. Retrieves the object using the AWS S3 SDK
4. Handles errors gracefully with retry and logging

### 3. Optional Decompression

If S3 content is compressed:
1. Retrieves compressed data from S3
2. Optionally decodes Base64
3. Decompresses using gzip/deflate/zlib
4. Returns decompressed content

### 4. Message Processing

The retrieved content replaces the S3 URI:
1. For entire body mode: replaces the whole message
2. For field path mode: replaces only the specified field
3. Delegates to the configured converter for final processing
4. Sends to Kafka topic

## Configuration

### Basic Configuration

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter"
}
```

### Complete Configuration Reference

| Property | Type | Description | Required | Default |
|----------|------|-------------|----------|---------|
| `message.converter.class` | String | Must be `ClaimCheckMessageConverter` | Yes | - |
| `message.claimcheck.delegate.converter.class` | String | Converter to use after S3 retrieval | No | `DefaultMessageConverter` |
| `message.claimcheck.field.path` | String | JSON path containing S3 URI (e.g., `detail.s3Key`) | No | - (treats entire body as S3 URI) |
| `message.claimcheck.decompress.enabled` | Boolean | Decompress S3 content after retrieval | No | `false` |
| `message.claimcheck.compression.format` | String | Format: `AUTO`, `GZIP`, `DEFLATE`, `ZLIB` | No | `AUTO` |
| `message.claimcheck.base64.decode` | Boolean | Base64 decode before decompression | No | `true` |

### AWS Credentials

The claim check feature uses the **same AWS credentials** configured for SQS access:

```json
{
  "aws.region": "us-east-1",
  "aws.access.key.id": "AKIAIOSFODNN7EXAMPLE",
  "aws.secret.access.key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}
```

**Supported Authentication Methods:**
- Static credentials (access key + secret key)
- AWS credentials profile
- Default credentials provider chain (environment variables, instance profile, ECS task role)
- STS Assume Role

**IAM Permissions Required:**

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject"
      ],
      "Resource": "arn:aws:s3:::my-bucket/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ],
      "Resource": "arn:aws:sqs:us-east-1:123456789012:my-queue"
    }
  ]
}
```

## Usage Examples

### Example 1: Simple S3 Reference

**Producer Code (Python):**

```python
import boto3
import json

s3 = boto3.client('s3')
sqs = boto3.client('sqs')

# Store large payload in S3
large_data = {"users": [...]}  # 5MB of data
s3.put_object(
    Bucket='my-bucket',
    Key='messages/msg-123.json',
    Body=json.dumps(large_data)
)

# Send S3 reference to SQS
sqs.send_message(
    QueueUrl='https://sqs.us-east-1.amazonaws.com/123456789012/my-queue',
    MessageBody='s3://my-bucket/messages/msg-123.json'
)
```

**Connector Configuration:**

```json
{
  "name": "sqs-source-s3-claimcheck",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789012/my-queue",
    "kafka.topic": "user-data",
    "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
    "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter",
    "aws.region": "us-east-1"
  }
}
```

**Result:** The 5MB JSON data from S3 is sent to the `user-data` Kafka topic.

---

### Example 2: EventBridge with Nested S3 Reference

**EventBridge Rule:**

When a large file is uploaded, EventBridge sends:

```json
{
  "version": "0",
  "id": "abc123-def456",
  "detail-type": "LargeDataUpdate",
  "source": "my.application",
  "account": "123456789012",
  "time": "2024-01-15T10:30:00Z",
  "region": "us-east-1",
  "detail": {
    "s3Key": "s3://my-bucket/data/large-dataset.json",
    "metadata": {
      "size": 5242880,
      "contentType": "application/json",
      "timestamp": "2024-01-15T10:30:00Z"
    }
  }
}
```

**Connector Configuration:**

```json
{
  "name": "eventbridge-sqs-s3-claimcheck",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789012/eventbridge-queue",
    "kafka.topic": "large-events",
    "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
    "message.claimcheck.field.path": "detail.s3Key",
    "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter",
    "aws.region": "us-east-1"
  }
}
```

**Result:**
- The S3 object content replaces `detail.s3Key` field
- The complete event (with retrieved data) is sent to Kafka
- Metadata fields remain unchanged

---

### Example 3: Compressed Data in S3

For compressed S3 objects:

**Producer Code:**

```python
import gzip
import base64
import json

# Compress data
data = json.dumps({"large": "payload"})
compressed = gzip.compress(data.encode())
encoded = base64.b64encode(compressed).decode()

# Store in S3
s3.put_object(
    Bucket='my-bucket',
    Key='compressed/data.json.gz',
    Body=encoded
)

# Send reference
sqs.send_message(
    QueueUrl='...',
    MessageBody=json.dumps({
        "s3Ref": "s3://my-bucket/compressed/data.json.gz"
    })
)
```

**Connector Configuration:**

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.field.path": "s3Ref",
  "message.claimcheck.decompress.enabled": "true",
  "message.claimcheck.compression.format": "GZIP",
  "message.claimcheck.base64.decode": "true",
  "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter"
}
```

**Processing Flow:**
1. Retrieve Base64-encoded gzipped data from S3
2. Decode Base64
3. Decompress gzip
4. Replace field with decompressed JSON
5. Send to Kafka

---

### Example 4: Avro Schema with S3 Claim Check

**Connector Configuration:**

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.field.path": "detail.s3Data",
  "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
  "schema.registry.url": "http://schema-registry:8081",
  "schema.subject.name": "user-data-value",
  "aws.region": "us-east-1"
}
```

**S3 Object Content (Avro JSON):**

```json
{
  "userId": 12345,
  "name": "John Doe",
  "email": "john@example.com",
  "orders": [...]
}
```

**Result:**
- S3 content is retrieved
- Validated against Avro schema
- Serialized and sent to Kafka

---

### Example 5: Mixed Mode (S3 and Regular Messages)

The claim check converter gracefully handles both:

**Message 1 (Regular):**
```json
{"event": "small-event", "data": "fits in SQS"}
```

**Message 2 (S3 Reference):**
```json
{"event": "large-event", "s3Data": "s3://bucket/large.json"}
```

**Configuration:**

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.field.path": "s3Data"
}
```

**Behavior:**
- Message 1: Passed through unchanged (no S3 URI detected)
- Message 2: S3 content retrieved and replaces `s3Data` field

## Advanced Use Cases

### Combining Claim Check with Message Filtering

Process only specific large messages:

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.field.path": "detail.s3Key",
  "message.filter.enabled": "true",
  "message.filter.policy": "{\"detail-type\":[\"LargeDataUpdate\",\"BulkImport\"]}"
}
```

### Multi-Tier Data Architecture

**Tier 1 - Hot Data (SQS):** Small, frequently accessed messages
**Tier 2 - Warm Data (S3):** Large messages retrieved on-demand
**Tier 3 - Cold Data (S3 Glacier):** Archived data with delayed retrieval

```json
{
  "message.converter.class": "io.connect.sqs.converter.ClaimCheckMessageConverter",
  "message.claimcheck.field.path": "s3Key",
  "sqs.visibility.timeout.seconds": "900"  // 15 min for Glacier retrieval
}
```

### Cross-Region S3 Access

Use S3 Transfer Acceleration for cross-region scenarios:

```bash
# Enable Transfer Acceleration on bucket
aws s3api put-bucket-accelerate-configuration \
  --bucket my-bucket \
  --accelerate-configuration Status=Enabled
```

Update S3 endpoint in connector:

```json
{
  "aws.endpoint.override": "https://my-bucket.s3-accelerate.amazonaws.com"
}
```

### S3 Versioning Support

Access specific S3 object versions:

```json
{
  "detail": {
    "s3Key": "s3://my-bucket/data.json?versionId=abc123"
  }
}
```

## Performance Considerations

### Latency

**Additional Latency:**
- S3 GetObject API call: 10-50ms (same region)
- Cross-region: 100-200ms
- S3 Transfer Acceleration: Reduces cross-region latency by ~50%

**Optimization Strategies:**
1. **Same Region:** Place S3 bucket in the same AWS region as SQS
2. **VPC Endpoint:** Use S3 VPC endpoint to avoid internet gateway
3. **Batch Processing:** Process multiple messages in parallel

### Throughput

**S3 API Limits:**
- GET requests: 5,500 requests/second per prefix
- Use different prefixes for high throughput: `s3://bucket/shard-1/`, `s3://bucket/shard-2/`

**Example Sharding Strategy:**

```python
import hashlib

def get_s3_key(message_id):
    shard = int(hashlib.md5(message_id.encode()).hexdigest(), 16) % 100
    return f"s3://my-bucket/shard-{shard}/data/{message_id}.json"
```

### Cost Optimization

**S3 Request Costs:**
- GET requests: $0.0004 per 1,000 requests
- Data transfer out: $0.09 per GB (to internet)
- Data transfer within same region: Free

**Example Cost Calculation:**

Processing 1 million messages/month with 1MB average S3 object size:
- S3 GET requests: 1,000,000 × $0.0004/1000 = $0.40
- Data transfer (same region): $0
- Total: **$0.40/month**

Compare to sending 1MB via SQS (requires chunking or won't work):
- Not possible (256KB limit)
- Claim check pattern is the only option

### Caching Strategies

For duplicate S3 references, implement caching:

**Application-Level Cache (Example):**

```java
// Pseudocode - implement in a custom converter wrapper
public class CachedClaimCheckConverter {
    private LoadingCache<String, byte[]> cache = CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build(s3Key -> s3Client.getObjectByUri(s3Key));
}
```

## Troubleshooting

### Issue: S3 Access Denied

**Symptoms:**
```
ConnectException: Claim check S3 retrieval failed:
Access Denied (Service: S3, Status Code: 403)
```

**Solutions:**

1. **Check IAM permissions:**
```bash
aws sts get-caller-identity  # Verify identity
aws s3 ls s3://my-bucket/    # Test S3 access
```

2. **Verify bucket policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {"AWS": "arn:aws:iam::123456789012:role/KafkaConnectRole"},
    "Action": "s3:GetObject",
    "Resource": "arn:aws:s3:::my-bucket/*"
  }]
}
```

3. **Check S3 Block Public Access settings** (if applicable)

---

### Issue: S3 Object Not Found

**Symptoms:**
```
ConnectException: Claim check S3 retrieval failed:
The specified key does not exist. (Service: S3, Status Code: 404)
```

**Solutions:**

1. **Verify S3 URI format:**
   - Correct: `s3://my-bucket/path/to/object.json`
   - Wrong: `https://s3.amazonaws.com/my-bucket/object.json`

2. **Check object lifecycle policies:**
```bash
aws s3api get-bucket-lifecycle-configuration --bucket my-bucket
```

3. **Enable S3 versioning** to protect against accidental deletion

---

### Issue: Decompression Failed

**Symptoms:**
```
ConnectException: Claim check S3 retrieval failed:
java.io.IOException: Not in GZIP format
```

**Solutions:**

1. **Verify compression format:**
```bash
aws s3 cp s3://my-bucket/data.json.gz - | file -
# Should show: gzip compressed data
```

2. **Check Base64 encoding:**
```json
{
  "message.claimcheck.base64.decode": "true"  // Enable if S3 content is Base64
}
```

3. **Use AUTO format detection:**
```json
{
  "message.claimcheck.compression.format": "AUTO"
}
```

---

### Issue: Slow Performance

**Symptoms:**
- High latency (>1 second per message)
- Low throughput

**Solutions:**

1. **Enable CloudWatch metrics:**
```bash
aws s3api put-bucket-metrics-configuration \
  --bucket my-bucket \
  --id EntireBucket \
  --metrics-configuration '{"Id":"EntireBucket"}'
```

2. **Use S3 VPC Endpoint:**
```bash
aws ec2 create-vpc-endpoint \
  --vpc-id vpc-abc123 \
  --service-name com.amazonaws.us-east-1.s3 \
  --route-table-ids rtb-123456
```

3. **Increase connector tasks:**
```json
{
  "tasks.max": "8"  // More parallel processing
}
```

4. **Monitor S3 request rate:**
```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/S3 \
  --metric-name AllRequests \
  --dimensions Name=BucketName,Value=my-bucket \
  --start-time 2024-01-15T00:00:00Z \
  --end-time 2024-01-15T23:59:59Z \
  --period 3600 \
  --statistics Sum
```

---

### Issue: Mixed Content Types

**Symptoms:**
Some S3 objects are JSON, others are binary

**Solution:**

Use content-type aware processing:

```json
{
  "message.claimcheck.delegate.converter.class": "io.connect.sqs.converter.DefaultMessageConverter"
}
```

Or implement custom converter to handle different content types.

## Best Practices

### 1. S3 Bucket Organization

**Use Prefixes for Sharding:**
```
s3://my-bucket/
  ├── shard-0/
  │   └── messages/
  ├── shard-1/
  │   └── messages/
  └── shard-2/
      └── messages/
```

**Lifecycle Policies:**
```json
{
  "Rules": [{
    "Id": "DeleteOldMessages",
    "Status": "Enabled",
    "Prefix": "messages/",
    "Expiration": {
      "Days": 7
    }
  }]
}
```

### 2. Error Handling

**Configure DLQ for failed S3 retrievals:**
```json
{
  "error.dlq.enabled": "true",
  "error.dlq.topic": "sqs-s3-errors"
}
```

**Monitor failures:**
```bash
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic sqs-s3-errors \
  --property print.headers=true
```

### 3. Security

**Encrypt S3 Objects:**
```bash
aws s3 cp local-file.json s3://my-bucket/encrypted/ \
  --server-side-encryption aws:kms \
  --ssekms-key-id alias/my-kms-key
```

**Use IAM Roles (not static credentials):**
```json
{
  "aws.credentials.provider": "default"
}
```

### 4. Monitoring

**Key Metrics to Track:**
- S3 GetObject request count
- S3 GetObject latency (p50, p99)
- Connector lag
- Error rate

**CloudWatch Dashboard Example:**
```json
{
  "widgets": [
    {
      "type": "metric",
      "properties": {
        "metrics": [
          ["AWS/S3", "AllRequests", {"stat": "Sum"}],
          [".", "GetRequests", {"stat": "Sum"}]
        ],
        "period": 300,
        "stat": "Average",
        "region": "us-east-1",
        "title": "S3 Request Rate"
      }
    }
  ]
}
```

### 5. Testing

**Test S3 Retrieval:**
```bash
# Upload test file
echo '{"test": "data"}' | aws s3 cp - s3://my-bucket/test.json

# Send SQS message
aws sqs send-message \
  --queue-url https://sqs.us-east-1.amazonaws.com/123456789012/my-queue \
  --message-body 's3://my-bucket/test.json'

# Verify in Kafka
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic my-topic \
  --from-beginning
```

### 6. Capacity Planning

**Calculate Required S3 Capacity:**

```
Messages/month: 10,000,000
Avg object size: 2 MB
Required S3 storage: 10M × 2 MB = 20 TB
S3 cost (standard): 20,000 GB × $0.023 = $460/month
GET requests: 10M × $0.0004/1000 = $4/month
Total: $464/month
```

**Compare to SQS limits:**
- SQS max: 256 KB
- Would require splitting messages or can't use SQS at all
- Claim check is the only viable solution for large messages

---

## Summary

The Claim Check Pattern implementation provides:

✅ **Handles messages >256KB** (up to 5TB via S3)
✅ **Cost-effective** storage and processing
✅ **Seamless integration** with existing converters
✅ **Optional compression** support
✅ **Flexible configuration** (entire body or field path)
✅ **Production-ready** with comprehensive error handling
✅ **AWS native** using standard S3 SDK

For additional help, see:
- [README.md](../README.md) - General connector documentation
- [SCHEMA_REGISTRY.md](SCHEMA_REGISTRY.md) - Schema validation
- [MESSAGE_DECOMPRESSION.md](MESSAGE_DECOMPRESSION.md) - Compression handling
