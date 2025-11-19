# Message Decompression

The Kafka Connect SQS Source Connector supports automatic decompression of compressed message data. This feature is particularly useful for:

- **EventBridge events** with compressed data in nested fields
- **Cost optimization** - sending compressed data through SQS reduces message sizes
- **Legacy systems** that send compressed payloads
- **High-throughput scenarios** where bandwidth is a constraint

## Table of Contents

- [Overview](#overview)
- [Supported Formats](#supported-formats)
- [Configuration](#configuration)
- [Usage Examples](#usage-examples)
- [Advanced Use Cases](#advanced-use-cases)
- [Performance Considerations](#performance-considerations)
- [Troubleshooting](#troubleshooting)

## Overview

The `DecompressingMessageConverter` is a wrapper converter that:

1. **Decompresses** message data (entire body or specific nested fields)
2. **Delegates** to another converter for final processing
3. **Auto-detects** compression format (gzip, deflate, zlib)
4. **Handles** Base64-encoded compressed data automatically
5. **Gracefully falls back** to original data if decompression fails

### Architecture

```
┌─────────────────┐
│   SQS Message   │
│  (Compressed)   │
└────────┬────────┘
         │
         ▼
┌─────────────────────────────────┐
│ DecompressingMessageConverter   │
│                                  │
│  1. Detect compression format   │
│  2. Decode Base64 (if needed)   │
│  3. Decompress data             │
│  4. Replace field/entire body   │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────────────────────┐
│    Delegate Converter           │
│  (Default, Avro, Protobuf, etc) │
└────────┬────────────────────────┘
         │
         ▼
┌─────────────────┐
│  Kafka Topic    │
└─────────────────┘
```

## Supported Formats

The connector auto-detects and supports the following compression formats:

| Format | Magic Bytes | Description | Common Use Cases |
|--------|-------------|-------------|------------------|
| **GZIP** | `0x1f 0x8b` | Standard gzip compression | Web APIs, AWS services, general purpose |
| **ZLIB** | `0x78 0x01/9c/da` | Deflate with zlib wrapper | Java applications, AWS SDK |
| **DEFLATE** | N/A | Raw deflate (uses zlib decoder) | Less common, legacy systems |

### Base64 Encoding

Compressed binary data in JSON is typically Base64-encoded. The connector automatically:

- **Detects** Base64-encoded strings using heuristics
- **Decodes** Base64 before decompression
- **Works with** both standard and URL-safe Base64 variants

## Configuration

### Basic Configuration

```properties
# Enable decompression by using the DecompressingMessageConverter
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter

# Specify which converter to use after decompression
message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter

# Optional: Specify compression format (default: AUTO)
message.decompression.format=AUTO

# Optional: Enable Base64 decoding (default: true)
message.decompression.base64.decode=true

# Optional: Specify field path for nested decompression (omit for entire body)
# message.decompression.field.path=detail.data
```

### Configuration Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `message.converter.class` | String | - | Must be `DecompressingMessageConverter` |
| `message.decompression.delegate.converter.class` | String | `DefaultMessageConverter` | Converter to use after decompression |
| `message.decompression.field.path` | String | `null` | Dot-notation path to nested field (e.g., `detail.data`). If not specified, decompresses entire message body. |
| `message.decompression.format` | String | `AUTO` | Compression format: `AUTO`, `GZIP`, `DEFLATE`, `ZLIB` |
| `message.decompression.base64.decode` | Boolean | `true` | Attempt Base64 decoding before decompression |

### Delegate Converters

You can use any message converter as the delegate:

- `io.connect.sqs.converter.DefaultMessageConverter` - Plain string messages
- `io.connect.sqs.converter.AvroMessageConverter` - Avro with Schema Registry
- `io.connect.sqs.converter.ProtobufMessageConverter` - Protocol Buffers with Schema Registry
- `io.connect.sqs.converter.JsonSchemaMessageConverter` - JSON Schema validation

## Usage Examples

### Example 1: Decompress Entire Message Body

**Use Case**: Your entire SQS message is gzip-compressed and Base64-encoded.

**SQS Message**:
```
H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA==
```

**Configuration**:
```properties
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
# No field path specified = decompress entire body
```

**Decompressed Result**:
```json
{"message":"Hello, World!","timestamp":1234567890}
```

### Example 2: EventBridge Events with Compressed detail.data

**Use Case**: AWS EventBridge events where the `detail.data` field contains compressed JSON.

**SQS Message**:
```json
{
  "version": "0",
  "id": "6a7e8feb-b491-4cf7-a9f1-bf3703467718",
  "detail-type": "PriceCache.Updated",
  "source": "PriceCacheService",
  "account": "891377401540",
  "time": "2025-11-18T07:20:37Z",
  "region": "eu-west-1",
  "resources": [],
  "detail": {
    "specversion": "1.0",
    "id": "event-123",
    "source": "PriceCacheService",
    "type": "PriceCache.Updated",
    "datacontenttype": "json",
    "time": "2025-11-18T07:20:36Z",
    "data": "H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA=="
  }
}
```

**Configuration**:
```properties
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
message.decompression.field.path=detail.data
message.decompression.format=AUTO
message.decompression.base64.decode=true
```

**Result** (after decompression):
```json
{
  "version": "0",
  "id": "6a7e8feb-b491-4cf7-a9f1-bf3703467718",
  "detail-type": "PriceCache.Updated",
  "source": "PriceCacheService",
  "account": "891377401540",
  "time": "2025-11-18T07:20:37Z",
  "region": "eu-west-1",
  "resources": [],
  "detail": {
    "specversion": "1.0",
    "id": "event-123",
    "source": "PriceCacheService",
    "type": "PriceCache.Updated",
    "datacontenttype": "json",
    "time": "2025-11-18T07:20:36Z",
    "data": "{\"price\":100,\"currency\":\"USD\",\"timestamp\":1234567890}"
  }
}
```

### Example 3: Decompression with Avro Schema Registry

**Use Case**: Decompress message and then convert to Avro with schema validation.

**Configuration**:
```properties
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
message.decompression.delegate.converter.class=io.connect.sqs.converter.AvroMessageConverter
message.decompression.field.path=detail.data

# Schema Registry configuration
schema.registry.url=http://schema-registry:8081
schema.auto.register=true
```

**Workflow**:
1. Decompress `detail.data` field
2. Pass decompressed message to AvroMessageConverter
3. Infer Avro schema from decompressed JSON
4. Register schema with Schema Registry
5. Serialize to Avro and send to Kafka

### Example 4: Deeply Nested Field Paths

**Use Case**: Decompress data buried deep in a JSON structure.

**SQS Message**:
```json
{
  "metadata": {
    "service": "payment-service",
    "version": "1.0"
  },
  "payload": {
    "body": {
      "compressed_content": "H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA=="
    }
  }
}
```

**Configuration**:
```properties
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
message.decompression.field.path=payload.body.compressed_content
```

**Result**:
```json
{
  "metadata": {
    "service": "payment-service",
    "version": "1.0"
  },
  "payload": {
    "body": {
      "compressed_content": "{\"message\":\"Hello, World!\"}"
    }
  }
}
```

## Advanced Use Cases

### Multiple Compressed Fields

If you have multiple compressed fields, you'll need to run separate connector instances or implement a custom transformer. The current implementation supports one field path per connector configuration.

**Workaround**: Deploy multiple connector instances, each handling one compressed field:

```properties
# Connector 1: Handles field A
name=sqs-connector-field-a
message.decompression.field.path=data.fieldA
kafka.topic=topic-field-a

# Connector 2: Handles field B
name=sqs-connector-field-b
message.decompression.field.path=data.fieldB
kafka.topic=topic-field-b
```

### Conditional Decompression

The decompressor gracefully handles messages that aren't compressed:

- If auto-detection finds no compression, returns original data
- If decompression fails, returns original data and logs warning
- Use `decompressSafe()` behavior for production resilience

### Custom Compression Formats

Currently supported formats: gzip, deflate, zlib. For custom formats:

1. Extend `MessageDecompressor` class
2. Add new `CompressionFormat` enum value
3. Implement decompression logic in `decompressBytes()`

## Performance Considerations

### Throughput Impact

Decompression adds CPU overhead but can improve overall throughput:

| Message Size | Compression Ratio | Network Savings | CPU Overhead | Net Benefit |
|--------------|-------------------|-----------------|--------------|-------------|
| Small (<10KB) | ~30% | Low | ~1-2ms | Minimal |
| Medium (10-100KB) | ~50% | Moderate | ~5-10ms | **Positive** |
| Large (>100KB) | ~60-70% | High | ~20-50ms | **Very Positive** |

**Recommendation**: Use decompression for messages >10KB for best performance.

### Memory Usage

- Decompression is streaming-based with 8KB buffer
- Peak memory: `compressed_size + decompressed_size`
- No significant heap pressure for typical messages (<1MB)

### Tuning Tips

1. **Format Override**: Use specific format instead of `AUTO` if known:
   ```properties
   message.decompression.format=GZIP  # Faster than auto-detection
   ```

2. **Disable Base64 Decoding**: If data is raw binary (not Base64):
   ```properties
   message.decompression.base64.decode=false
   ```

3. **Batch Size**: Increase for better amortization of overhead:
   ```properties
   sqs.max.messages=10  # Maximum allowed by SQS
   ```

## Troubleshooting

### Common Issues

#### 1. "Decompression failed: incorrect header check"

**Cause**: Data is not actually compressed or wrong format specified.

**Solutions**:
- Verify data is compressed: `echo "data" | base64 -d | file -`
- Use `AUTO` format detection
- Check if data is raw deflate (rare) vs. zlib

#### 2. "Unrecognized token" or JSON parsing errors

**Cause**: Field path doesn't exist or points to non-compressed data.

**Solutions**:
- Verify field path with: `echo "$message" | jq '.detail.data'`
- Check field is a string, not object/array
- Ensure JSON structure matches configuration

#### 3. Original compressed data returned (no decompression)

**Cause**: Auto-detection failed or data isn't Base64-encoded.

**Solutions**:
- Check if data needs Base64 decoding
- Try explicit format: `message.decompression.format=GZIP`
- Verify data starts with magic bytes (0x1f 0x8b for gzip)

#### 4. "Failed to instantiate delegate converter"

**Cause**: Delegate converter class not found or misconfigured.

**Solutions**:
- Verify class name is fully qualified
- Check connector JAR includes required dependencies
- For Schema Registry converters, ensure URL is configured

### Debugging

Enable debug logging:

```properties
# In connect-log4j.properties
log4j.logger.io.connect.sqs.converter.DecompressingMessageConverter=DEBUG
log4j.logger.io.connect.sqs.util.MessageDecompressor=DEBUG
```

**Debug Output**:
```
DEBUG DecompressingMessageConverter - Decompressing field path: detail.data
DEBUG MessageDecompressor - Auto-detected compression format: GZIP
DEBUG MessageDecompressor - Successfully decoded Base64 data, 57 bytes
DEBUG MessageDecompressor - Decompressed 57 bytes to 50 bytes
```

### Testing Decompression Locally

Create a test compressed message:

```bash
# Create compressed data
echo '{"message":"Hello, World!"}' | gzip | base64

# Result: H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA==

# Test decompression
echo "H4sIAAAAAAAA/6tWKkktLlGyUlAqS8wpTtVRKi1OLYrPTSwpSs2zUgKpBQBZvhNoIwAAAA==" | \
  base64 -d | gunzip

# Should output: {"message":"Hello, World!"}
```

## Best Practices

1. **Use Field Path for Partial Decompression**: Only decompress what's needed to reduce CPU usage.

2. **Enable Auto-Detection**: Use `AUTO` format unless you have specific requirements.

3. **Monitor Performance**: Track decompression time in production:
   ```properties
   # Enable JMX metrics
   connector.client.config.override.policy=All
   ```

4. **Test with Production Data**: Validate decompression with real messages before deployment.

5. **Handle Failures Gracefully**: The decompressor returns original data on failure - monitor logs for decompression errors.

6. **Consider Alternatives**: For very high throughput (>100k msgs/sec), consider:
   - Decompressing in producer before SQS
   - Using Lambda for decompression before Kafka
   - Hardware acceleration (if available)

## Examples Repository

Complete working examples are available in the `examples/` directory:

- `eventbridge-decompression.json` - EventBridge with compressed detail.data
- `full-body-decompression.json` - Entire message body compressed
- `nested-field-decompression.json` - Deeply nested compressed fields
- `avro-with-decompression.json` - Decompression + Avro conversion

## See Also

- [Schema Registry Documentation](SCHEMA_REGISTRY.md) - Using decompression with schema validation
- [Configuration Reference](../README.md#configuration) - Complete configuration options
- [Troubleshooting Guide](../README.md#troubleshooting) - General connector issues
