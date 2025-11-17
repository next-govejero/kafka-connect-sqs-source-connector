# Schema Registry Support

This document provides comprehensive guidance on using Schema Registry with the Kafka Connect SQS Source Connector. The connector supports Avro, Protobuf, and JSON Schema formats through integration with Confluent Schema Registry.

## Table of Contents

- [Overview](#overview)
- [Prerequisites](#prerequisites)
- [Configuration Reference](#configuration-reference)
- [Message Converters](#message-converters)
  - [AvroMessageConverter](#avromessageconverter)
  - [ProtobufMessageConverter](#protobufmessageconverter)
  - [JsonSchemaMessageConverter](#jsonschemamessageconverter)
- [Usage Examples](#usage-examples)
  - [Basic Avro Example](#basic-avro-example)
  - [Protobuf with Authentication](#protobuf-with-authentication)
  - [JSON Schema with SSL](#json-schema-with-ssl)
  - [Using Specific Schema IDs](#using-specific-schema-ids)
  - [Multi-Queue with Schema Registry](#multi-queue-with-schema-registry)
- [Schema Evolution](#schema-evolution)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

## Overview

Schema Registry support enables enterprise-grade schema validation and evolution for messages flowing from SQS to Kafka. The connector automatically:

- Infers schemas from JSON messages
- Registers schemas with Confluent Schema Registry
- Validates messages against schemas
- Supports schema evolution (backward/forward compatibility)
- Converts messages to typed Kafka Connect data structures

## Prerequisites

1. **Confluent Schema Registry** running and accessible
2. **Schema Registry dependencies** included in connector deployment:
   - `kafka-schema-registry-client`
   - `kafka-avro-serializer` (for Avro)
   - `kafka-protobuf-serializer` (for Protobuf)
   - `kafka-json-schema-serializer` (for JSON Schema)

### Dependency Inclusion

When deploying the connector, ensure Schema Registry JARs are in the connector's classpath:

```bash
# Copy required JARs to connector plugin directory
cp kafka-schema-registry-client-*.jar /connect/plugins/kafka-connect-sqs-source/
cp kafka-avro-serializer-*.jar /connect/plugins/kafka-connect-sqs-source/
cp avro-*.jar /connect/plugins/kafka-connect-sqs-source/
```

## Configuration Reference

### Schema Registry Settings

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `schema.registry.url` | String | null | URL of Schema Registry (required for schema-based converters) |
| `value.schema.id` | Integer | null | Specific schema ID to use for value serialization |
| `key.schema.id` | Integer | null | Specific schema ID to use for key serialization |
| `schema.auto.register` | Boolean | true | Enable automatic schema registration |
| `schema.use.latest.version` | Boolean | false | Use latest schema version from registry |
| `schema.subject.name.strategy` | String | TopicNameStrategy | Strategy for schema subject naming |
| `schema.registry.basic.auth.credentials.source` | String | USER_INFO | Source of auth credentials |
| `schema.registry.basic.auth.user.info` | Password | null | Username:password for basic auth |
| `schema.registry.ssl.truststore.location` | String | null | SSL truststore location |
| `schema.registry.ssl.truststore.password` | Password | null | SSL truststore password |

### Message Converter Configuration

| Configuration | Type | Default | Description |
|--------------|------|---------|-------------|
| `message.converter.class` | String | DefaultMessageConverter | Converter class to use |

Available converters:
- `io.connect.sqs.converter.DefaultMessageConverter` - Plain string (no schema)
- `io.connect.sqs.converter.AvroMessageConverter` - Apache Avro with Schema Registry
- `io.connect.sqs.converter.ProtobufMessageConverter` - Protocol Buffers with Schema Registry
- `io.connect.sqs.converter.JsonSchemaMessageConverter` - JSON Schema with Schema Registry

## Message Converters

### AvroMessageConverter

Converts JSON messages from SQS to Avro format with automatic schema inference.

**Features:**
- Automatic schema generation from JSON structure
- Support for nested objects and arrays
- Nullable field handling
- Complex type mapping (int, long, double, boolean, string, arrays, records)

**Example SQS Message:**
```json
{
  "orderId": "ORD-12345",
  "customer": {
    "id": 1001,
    "email": "customer@example.com"
  },
  "items": ["SKU-001", "SKU-002"],
  "total": 299.99,
  "paid": true
}
```

**Generated Avro Schema:**
```json
{
  "type": "record",
  "name": "orders_topic_value",
  "namespace": "io.connect.sqs.avro",
  "fields": [
    {"name": "orderId", "type": ["null", "string"]},
    {"name": "customer", "type": ["null", {
      "type": "record",
      "name": "customer_record",
      "fields": [
        {"name": "id", "type": ["null", "int"]},
        {"name": "email", "type": ["null", "string"]}
      ]
    }]},
    {"name": "items", "type": ["null", {"type": "array", "items": "string"}]},
    {"name": "total", "type": ["null", "double"]},
    {"name": "paid", "type": ["null", "boolean"]}
  ]
}
```

### ProtobufMessageConverter

Converts JSON messages to Protocol Buffers format.

**Features:**
- Automatic proto3 schema generation
- Support for repeated fields (arrays)
- Various data types (int32, int64, double, bool, string)
- Efficient binary serialization

**Example SQS Message:**
```json
{
  "userId": "user-123",
  "actions": ["login", "view", "purchase"],
  "timestamp": 1234567890
}
```

**Generated Protobuf Schema:**
```protobuf
syntax = "proto3";

package io.connect.sqs.protobuf;

message events_topic_value {
  string userId = 1;
  repeated string actions = 2;
  int64 timestamp = 3;
}
```

### JsonSchemaMessageConverter

Uses JSON Schema for validation while maintaining JSON format.

**Features:**
- JSON Schema Draft-07 support
- Schema validation
- Type inference and enforcement
- Nested object support

**Example SQS Message:**
```json
{
  "eventType": "USER_SIGNUP",
  "payload": {
    "userId": "new-user-456",
    "email": "new@example.com"
  },
  "metadata": {
    "timestamp": 1234567890,
    "version": 1
  }
}
```

**Generated JSON Schema:**
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "events_value",
  "type": "object",
  "properties": {
    "eventType": {"type": "string"},
    "payload": {
      "type": "object",
      "properties": {
        "userId": {"type": "string"},
        "email": {"type": "string"}
      }
    },
    "metadata": {
      "type": "object",
      "properties": {
        "timestamp": {"type": "integer"},
        "version": {"type": "integer"}
      }
    }
  },
  "required": ["eventType", "payload", "metadata"]
}
```

## Usage Examples

### Basic Avro Example

Simple setup with automatic schema registration:

```json
{
  "name": "sqs-avro-connector",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",

    "aws.region": "us-east-1",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789/orders-queue",

    "kafka.topic": "orders",

    "message.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
    "schema.registry.url": "http://schema-registry:8081",
    "schema.auto.register": "true"
  }
}
```

### Protobuf with Authentication

Protobuf converter with Schema Registry authentication:

```json
{
  "name": "sqs-protobuf-connector",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "2",

    "aws.region": "us-west-2",
    "aws.access.key.id": "AKIAIOSFODNN7EXAMPLE",
    "aws.secret.access.key": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    "sqs.queue.url": "https://sqs.us-west-2.amazonaws.com/123456789/events-queue",

    "kafka.topic": "events",

    "message.converter.class": "io.connect.sqs.converter.ProtobufMessageConverter",
    "schema.registry.url": "http://schema-registry:8081",
    "schema.registry.basic.auth.credentials.source": "USER_INFO",
    "schema.registry.basic.auth.user.info": "admin:admin-secret",
    "schema.auto.register": "true"
  }
}
```

### JSON Schema with SSL

JSON Schema converter with SSL configuration:

```json
{
  "name": "sqs-jsonschema-connector",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",

    "aws.region": "eu-west-1",
    "aws.assume.role.arn": "arn:aws:iam::123456789:role/SQSAccessRole",
    "sqs.queue.url": "https://sqs.eu-west-1.amazonaws.com/123456789/audit-queue",

    "kafka.topic": "audit-logs",

    "message.converter.class": "io.connect.sqs.converter.JsonSchemaMessageConverter",
    "schema.registry.url": "https://schema-registry.example.com:8081",
    "schema.registry.ssl.truststore.location": "/etc/kafka/secrets/truststore.jks",
    "schema.registry.ssl.truststore.password": "truststore-password",
    "schema.auto.register": "true"
  }
}
```

### Using Specific Schema IDs

Use pre-registered schemas by ID:

```json
{
  "name": "sqs-schema-id-connector",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",

    "aws.region": "us-east-1",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789/typed-queue",

    "kafka.topic": "typed-messages",

    "message.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
    "schema.registry.url": "http://schema-registry:8081",
    "value.schema.id": "100",
    "schema.auto.register": "false"
  }
}
```

### Multi-Queue with Schema Registry

Process multiple queues with Avro schema support:

```json
{
  "name": "sqs-multi-queue-avro",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "3",

    "aws.region": "us-east-1",
    "sqs.queue.urls": "https://sqs.us-east-1.amazonaws.com/123456789/queue1,https://sqs.us-east-1.amazonaws.com/123456789/queue2,https://sqs.us-east-1.amazonaws.com/123456789/queue3",

    "kafka.topic": "aggregated-events",

    "message.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
    "schema.registry.url": "http://schema-registry:8081",
    "schema.auto.register": "true",
    "schema.subject.name.strategy": "io.confluent.kafka.serializers.subject.TopicNameStrategy"
  }
}
```

### FIFO Queue with Schema Validation

FIFO queue with JSON Schema validation:

```json
{
  "name": "sqs-fifo-jsonschema",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",

    "aws.region": "us-east-1",
    "sqs.queue.url": "https://sqs.us-east-1.amazonaws.com/123456789/orders.fifo",
    "sqs.fifo.queue": "true",
    "sqs.fifo.deduplication.enabled": "true",

    "kafka.topic": "ordered-events",

    "message.converter.class": "io.connect.sqs.converter.JsonSchemaMessageConverter",
    "schema.registry.url": "http://schema-registry:8081",
    "schema.use.latest.version": "true"
  }
}
```

### Production Configuration with Full Features

Complete production setup with all features:

```json
{
  "name": "production-sqs-connector",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "5",

    "aws.region": "us-east-1",
    "aws.assume.role.arn": "arn:aws:iam::123456789:role/ProductionSQSRole",
    "aws.sts.role.session.name": "kafka-connect-prod",
    "sqs.queue.urls": "https://sqs.us-east-1.amazonaws.com/123456789/prod-queue1,https://sqs.us-east-1.amazonaws.com/123456789/prod-queue2",
    "sqs.max.messages": "10",
    "sqs.wait.time.seconds": "20",
    "sqs.visibility.timeout.seconds": "60",
    "sqs.delete.messages": "true",

    "kafka.topic": "production-events",

    "dlq.topic": "production-events-dlq",
    "max.retries": "5",
    "retry.backoff.ms": "2000",

    "message.converter.class": "io.connect.sqs.converter.AvroMessageConverter",
    "schema.registry.url": "https://schema-registry.prod.example.com:8081",
    "schema.registry.basic.auth.credentials.source": "USER_INFO",
    "schema.registry.basic.auth.user.info": "prod-user:${file:/secrets/schema-registry-password}",
    "schema.registry.ssl.truststore.location": "/etc/kafka/secrets/prod-truststore.jks",
    "schema.registry.ssl.truststore.password": "${file:/secrets/truststore-password}",
    "schema.auto.register": "true",
    "schema.subject.name.strategy": "io.confluent.kafka.serializers.subject.TopicNameStrategy"
  }
}
```

## Schema Evolution

The connector supports schema evolution when `schema.auto.register` is enabled:

### Backward Compatibility
- New schema can read old data
- Add optional fields with defaults
- Remove fields

### Forward Compatibility
- Old schema can read new data
- Add fields
- Remove optional fields

### Full Compatibility
- Both backward and forward compatible

**Example: Adding a new field**

Original message:
```json
{"name": "John", "age": 30}
```

Evolved message (new field with default):
```json
{"name": "Jane", "age": 25, "email": "jane@example.com"}
```

The schema registry automatically manages this evolution, ensuring consumers can handle both old and new formats.

## Best Practices

1. **Use Specific Schema IDs in Production**: For critical systems, use `value.schema.id` to ensure consistent schemas.

2. **Enable Schema Caching**: The connector caches schemas for performance. Ensure adequate memory for schema cache.

3. **Monitor Schema Registry**: Set up monitoring for:
   - Schema registration failures
   - Schema compatibility issues
   - Schema Registry availability

4. **Test Schema Evolution**: Before deploying changes, test schema compatibility:
   ```bash
   curl -X POST http://schema-registry:8081/compatibility/subjects/topic-value/versions/latest \
     -H "Content-Type: application/json" \
     -d '{"schema": "{...}"}'
   ```

5. **Use Message Attributes for Routing**: Leverage SQS message attributes in Kafka headers for downstream processing.

6. **Configure Dead Letter Queue**: Always configure DLQ for schema validation failures:
   ```json
   {
     "dlq.topic": "schema-errors-dlq",
     "max.retries": "3"
   }
   ```

7. **Secure Schema Registry**: Use authentication and SSL in production environments.

## Troubleshooting

### Common Issues

**Schema Registry Connection Failed**
```
Error: Failed to connect to Schema Registry
```
Solution: Verify `schema.registry.url` is correct and accessible from connector workers.

**Schema Registration Failed**
```
Error: Failed to register schema
```
Solution: Check Schema Registry permissions and ensure `schema.auto.register` is true.

**Schema Compatibility Error**
```
Error: Schema evolution violates compatibility
```
Solution: Check schema compatibility settings in Schema Registry. Consider using `BACKWARD` or `NONE` compatibility.

**Invalid JSON Format**
```
Warning: Failed to parse message as JSON, using raw string
```
Solution: Ensure SQS messages are valid JSON. Non-JSON messages are passed through as strings.

### Logging

Enable detailed logging for schema operations:

```properties
log4j.logger.io.connect.sqs.converter=DEBUG
log4j.logger.io.confluent.kafka.schemaregistry=DEBUG
```

### Metrics

Monitor these key metrics:
- `messages-converted` - Number of successfully converted messages
- `schema-registrations` - Number of schema registrations
- `conversion-errors` - Number of conversion failures

## Performance Considerations

- **Schema Caching**: Schemas are cached to avoid repeated registry calls
- **Message Batching**: Configure `sqs.max.messages` for optimal throughput
- **Parallel Processing**: Use multiple tasks for high-throughput scenarios
- **Memory Usage**: Schema caching requires memory; monitor heap usage

## Conclusion

Schema Registry integration provides enterprise-grade data governance for SQS-to-Kafka pipelines. By leveraging Avro, Protobuf, or JSON Schema, organizations can ensure data quality, enable schema evolution, and build robust data infrastructure.

For more information, refer to:
- [Confluent Schema Registry Documentation](https://docs.confluent.io/platform/current/schema-registry/index.html)
- [Apache Avro Specification](https://avro.apache.org/docs/current/spec.html)
- [Protocol Buffers Documentation](https://developers.google.com/protocol-buffers)
- [JSON Schema Specification](https://json-schema.org/)
