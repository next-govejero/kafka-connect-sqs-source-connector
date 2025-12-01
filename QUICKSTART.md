# Developer Quickstart Guide

Welcome! This guide will help you understand the codebase in **30 minutes**. If you're new to Kafka Connect or this project, start here.

## Table of Contents
- [5-Minute Overview](#5-minute-overview)
- [Architecture at a Glance](#architecture-at-a-glance)
- [Key Classes (Start Here)](#key-classes-start-here)
- [Reading Path](#reading-path-30-minutes)
- [How It Works: Message Flow](#how-it-works-message-flow)
- [Common Developer Tasks](#common-developer-tasks)
- [Package Structure](#package-structure)
- [Debugging Tips](#debugging-tips)

---

## 5-Minute Overview

### What This Does
This is a **Kafka Connect source connector** that pulls messages from AWS SQS queues and pushes them into Kafka topics.

```
AWS SQS Queue → [This Connector] → Kafka Topic
```

### How Kafka Connect Works (Quick Primer)
If you're new to Kafka Connect:
1. **Connector**: Configuration & orchestration (splits work into tasks)
2. **Task**: Does the actual work (polls SQS, produces to Kafka)
3. Kafka Connect framework calls your connector/task methods automatically

### Core Responsibilities
This connector handles:
- ✅ Polling messages from SQS (long polling, batching)
- ✅ Converting message formats (Avro, Protobuf, JSON Schema, decompression)
- ✅ Retrying failed messages (exponential backoff with jitter)
- ✅ Dead letter queue routing (when retries exhausted)
- ✅ FIFO queue support (ordering, deduplication)
- ✅ Message filtering (client-side attribute filtering)
- ✅ Multi-queue support (parallel processing)

---

## Architecture at a Glance

### High-Level Flow
```
┌─────────────────────────────────────────────────────────────────┐
│                    Kafka Connect Framework                      │
│  (Calls our connector lifecycle methods automatically)          │
└───────────┬─────────────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────────┐
│  SqsSourceConnector (Entry Point)                             │
│  - Validates configuration                                     │
│  - Creates task configs (multi-queue distribution)             │
│  src/main/java/io/connect/sqs/SqsSourceConnector.java         │
└───────────┬───────────────────────────────────────────────────┘
            │ creates
            ▼
┌───────────────────────────────────────────────────────────────┐
│  SqsSourceTask (Worker)                                        │
│  - poll() method called by framework repeatedly                │
│  - Orchestrates the entire message flow                        │
│  src/main/java/io/connect/sqs/SqsSourceTask.java              │
└───────┬───────────────────────────────────────────────────────┘
        │
        ├──→ SqsClient (aws/SqsClient.java)
        │    └─ receiveMessages() → AWS SQS API
        │    └─ deleteMessages()  → AWS SQS API
        │
        ├──→ MessageFilterProcessor (filter/MessageFilterProcessor.java)
        │    └─ Filters messages by attributes
        │
        ├──→ MessageConverter (converter/*.java)
        │    └─ Transforms SQS message → Kafka record
        │    └─ Supports: Avro, Protobuf, JSON Schema, Decompression
        │
        ├──→ RetryManager (retry/RetryManager.java)
        │    └─ Decides: retry or DLQ?
        │    └─ Calculates exponential backoff
        │
        └──→ DeduplicationTracker (fifo/DeduplicationTracker.java)
             └─ Prevents duplicate processing (FIFO only)
```

### Component Relationships
```
┌─────────────┐
│   Config    │ ← Parsed once, used everywhere
└──────┬──────┘
       │
       ▼
┌──────────────────────────────────────────────────┐
│              SqsSourceTask                       │
│  (Orchestrator - coordinates everything)         │
└──┬────┬────┬──────┬────────┬─────────────────┬──┘
   │    │    │      │        │                 │
   ▼    ▼    ▼      ▼        ▼                 ▼
 SQS  Filter Conv  Retry  Dedup             Kafka
Client      er    Manager Tracker          (output)
```

---

## Key Classes (Start Here)

These are the **5 most important classes** to understand. Read them in this order:

### 1. `SqsSourceConnectorConfig.java`
**Location:** `src/main/java/io/connect/sqs/config/SqsSourceConnectorConfig.java`
**Purpose:** Defines all configuration options (queue URL, credentials, retry settings, etc.)
**Key Methods:**
- Configuration constants (e.g., `SQS_QUEUE_URL_CONFIG`)
- Getters for each config value
**Why read this first:** Understanding the config helps you understand what the connector can do.

### 2. `SqsSourceConnector.java`
**Location:** `src/main/java/io/connect/sqs/SqsSourceConnector.java`
**Purpose:** Entry point for Kafka Connect framework
**Key Methods:**
- `start()` - Called when connector starts
- `taskConfigs()` - Distributes queues across tasks
- `stop()` - Cleanup
**What to notice:** How multi-queue configs are split into task configs.

### 3. `SqsSourceTask.java`
**Location:** `src/main/java/io/connect/sqs/SqsSourceTask.java`
**Purpose:** The worker that does the actual polling and processing
**Key Methods:**
- `poll()` - Called repeatedly by framework to get messages (lines 117+)
- `start()` - Initialize clients, converters, retry manager (lines 65-114)
- `commitRecord()` - Called after Kafka confirms write (lines 390+)
**Why read this:** This is where the magic happens. `poll()` is the heart of the connector.

### 4. `SqsClient.java`
**Location:** `src/main/java/io/connect/sqs/aws/SqsClient.java`
**Purpose:** Wrapper around AWS SDK for SQS operations
**Key Methods:**
- `receiveMessages()` - Long polling from SQS (lines 60+)
- `deleteMessages()` - Batch delete after processing (lines 90+)
- `createCredentialsProvider()` - Handles AWS auth (lines 178+)
**What to notice:** How it supports multiple auth methods (IAM roles, access keys, profiles).

### 5. `MessageConverter.java` (interface) + implementations
**Location:** `src/main/java/io/connect/sqs/converter/`
**Purpose:** Transforms SQS message → Kafka record
**Key Implementations:**
- `DefaultMessageConverter.java` - Simple string passthrough
- `DecompressingMessageConverter.java` - Decompresses gzip/deflate data
- `ClaimCheckMessageConverter.java` - Retrieves large messages from S3
- `AvroMessageConverter.java` - Schema Registry integration
**What to notice:** Decorator pattern - converters can wrap each other.

---

## Reading Path (30 Minutes)

Follow this order to understand the codebase from simple to complex:

### Phase 1: Configuration (5 min)
1. **Read:** `config/SqsSourceConnectorConfig.java` (lines 1-200)
   - Skim the config constants
   - Notice the groups: AWS, SQS, Kafka, Retry, FIFO

### Phase 2: Entry Point (5 min)
2. **Read:** `SqsSourceConnector.java` (entire file, ~120 lines)
   - Focus on `taskConfigs()` method
   - See how multi-queue URLs are distributed

### Phase 3: Core Logic (10 min)
3. **Read:** `SqsSourceTask.java` (lines 1-200)
   - Understand the `start()` method initialization
   - Focus on the `poll()` method - this is the main loop
   - Notice: receive → filter → convert → retry decision → return to framework

4. **Read:** `aws/SqsClient.java` (lines 1-100, 178-260)
   - See how AWS SDK is wrapped
   - Understand credential resolution logic

### Phase 4: Processing Pipeline (10 min)
5. **Read:** `converter/DefaultMessageConverter.java` (entire file, simple)
   - Understand the `MessageConverter` interface
   - See how SQS message becomes Kafka `SourceRecord`

6. **Read:** `retry/RetryManager.java` (focus on lines 54-105)
   - Understand `recordFailure()` logic
   - See exponential backoff calculation with jitter

7. **Read:** `filter/MessageFilterProcessor.java` (lines 1-100)
   - See how message attributes are filtered

### Phase 5: Advanced Features (Optional)
8. **Read:** `converter/DecompressingMessageConverter.java`
   - Decorator pattern in action
   - Field path extraction logic

9. **Read:** `fifo/DeduplicationTracker.java`
   - Simple time-based deduplication

---

## How It Works: Message Flow

### Detailed Walkthrough: What Happens When a Message Arrives?

Let's trace a single message through the system:

#### Step 1: Framework Calls `poll()`
**File:** `SqsSourceTask.java:117`
```java
public List<SourceRecord> poll() throws InterruptedException {
    // Kafka Connect calls this repeatedly in a loop
```

#### Step 2: Receive from SQS
**File:** `SqsSourceTask.java:122`
```java
List<Message> messages = sqsClient.receiveMessages();
```
**What happens:**
- `SqsClient.receiveMessages()` makes AWS SDK call
- Uses long polling (waits up to `sqs.wait.time.seconds`)
- Batch size: 1-10 messages (configured by `sqs.max.messages`)

#### Step 3: Filter Messages (If Configured)
**File:** `SqsSourceTask.java:134-143`
```java
if (messageFilterProcessor.hasFilterPolicy()) {
    List<Message> matchingMessages = messageFilterProcessor.filter(messages);
```
**What happens:**
- Checks message attributes against filter policy JSON
- Non-matching messages are deleted from SQS immediately

#### Step 4: Check Deduplication (FIFO Only)
**File:** `SqsSourceTask.java:160-171`
```java
if (deduplicationTracker != null) {
    if (deduplicationTracker.isDuplicate(deduplicationId)) {
        // Skip this message, delete from SQS
```

#### Step 5: Convert to Kafka Record
**File:** `SqsSourceTask.java:175-195`
```java
SourceRecord record = messageConverter.convert(message, config);
```
**What happens:**
- Converter chain executes (e.g., Decompress → Avro → Field Extract)
- SQS message body becomes Kafka record value
- SQS attributes become Kafka headers
- Message ID becomes Kafka key

#### Step 6: Check Retry Status
**File:** `SqsSourceTask.java:185-195`
```java
if (!retryManager.canRetryNow(messageId)) {
    // Still in backoff period, skip this message
```

#### Step 7: Return to Framework
**File:** `SqsSourceTask.java:240-242`
```java
return sourceRecords.isEmpty() ? null : sourceRecords;
```
**What happens:**
- Framework receives list of `SourceRecord`s
- Framework writes them to Kafka asynchronously
- Framework calls `commitRecord()` after Kafka confirms

#### Step 8: Commit (Delete from SQS)
**File:** `SqsSourceTask.java:390-415`
```java
public void commitRecord(SourceRecord record, RecordMetadata metadata) {
    // Called by framework after Kafka write succeeds
    sqsClient.deleteMessages(messagesToDelete);
```

---

## Common Developer Tasks

### Task 1: Add a New Configuration Option

**Example:** Add a new config `sqs.custom.timeout`

1. **Define constant in `SqsSourceConnectorConfig.java`:**
```java
public static final String SQS_CUSTOM_TIMEOUT_CONFIG = "sqs.custom.timeout";
private static final String SQS_CUSTOM_TIMEOUT_DOC = "Custom timeout in seconds";
private static final int SQS_CUSTOM_TIMEOUT_DEFAULT = 60;
```

2. **Add to ConfigDef (in `configDef()` method):**
```java
.define(SQS_CUSTOM_TIMEOUT_CONFIG,
        Type.INT,
        SQS_CUSTOM_TIMEOUT_DEFAULT,
        Importance.MEDIUM,
        SQS_CUSTOM_TIMEOUT_DOC,
        "SQS", ++order, Width.SHORT, "Custom Timeout")
```

3. **Add getter method:**
```java
public int getSqsCustomTimeout() {
    return getInt(SQS_CUSTOM_TIMEOUT_CONFIG);
}
```

4. **Use in code (e.g., `SqsClient.java`):**
```java
int customTimeout = config.getSqsCustomTimeout();
```

5. **Document in README.md** (Configuration section)

### Task 2: Add a New Message Converter

**Example:** Create a custom Base64 decoder converter

1. **Create new file:** `src/main/java/io/connect/sqs/converter/Base64DecoderConverter.java`

2. **Implement `MessageConverter` interface:**
```java
public class Base64DecoderConverter implements MessageConverter {
    @Override
    public SourceRecord convert(Message message, SqsSourceConnectorConfig config) {
        String decoded = new String(Base64.getDecoder().decode(message.body()));
        // ... create SourceRecord
    }
}
```

3. **Add configuration:**
```java
message.converter.class=io.connect.sqs.converter.Base64DecoderConverter
```

4. **Write tests:** `src/test/java/io/connect/sqs/converter/Base64DecoderConverterTest.java`

5. **Document in README.md** (Message Format Configuration section)

### Task 3: Debug Locally with LocalStack

**Prerequisites:** Docker Desktop running

1. **Start LocalStack:**
```bash
docker-compose up -d localstack
```

2. **Create SQS queue:**
```bash
aws --endpoint-url=http://localhost:4566 sqs create-queue --queue-name test-queue
```

3. **Configure connector for LocalStack:**
```properties
aws.endpoint.override=http://localhost:4566
sqs.queue.url=http://localhost:4566/000000000000/test-queue
```

4. **Send test message:**
```bash
aws --endpoint-url=http://localhost:4566 sqs send-message \
  --queue-url http://localhost:4566/000000000000/test-queue \
  --message-body "Test message"
```

5. **Run connector in IDE:**
- Set breakpoint in `SqsSourceTask.poll()`
- Run Kafka Connect standalone mode

### Task 4: Run Tests

**Unit tests only:**
```bash
mvn test
```

**Integration tests (requires Docker):**
```bash
export RUN_INTEGRATION_TESTS=true
mvn verify
```

**Specific test class:**
```bash
mvn test -Dtest=SqsClientTest
```

**With coverage report:**
```bash
mvn clean test jacoco:report
# Open: target/site/jacoco/index.html
```

---

## Package Structure

```
src/main/java/io/connect/sqs/
│
├── SqsSourceConnector.java       # Entry point, called by Kafka Connect
├── SqsSourceTask.java             # Main worker, poll() logic
│
├── aws/                           # AWS SDK wrappers
│   ├── SqsClient.java             # SQS operations (receive, delete)
│   └── S3Client.java              # S3 operations (claim check pattern)
│
├── config/                        # Configuration
│   └── SqsSourceConnectorConfig.java  # All config definitions
│
├── converter/                     # Message transformation
│   ├── MessageConverter.java      # Interface
│   ├── DefaultMessageConverter.java       # Simple passthrough
│   ├── DecompressingMessageConverter.java # Gzip/deflate support
│   ├── ClaimCheckMessageConverter.java    # S3 retrieval
│   ├── AvroMessageConverter.java          # Schema Registry + Avro
│   ├── ProtobufMessageConverter.java      # Schema Registry + Protobuf
│   ├── JsonSchemaMessageConverter.java    # Schema Registry + JSON Schema
│   └── FieldExtractorConverter.java       # Extract nested fields
│
├── retry/                         # Retry & error handling
│   ├── RetryManager.java          # Exponential backoff logic
│   └── RetryDecision.java         # Retry vs DLQ decision
│
├── filter/                        # Message filtering
│   └── MessageFilterProcessor.java  # Attribute-based filtering
│
├── fifo/                          # FIFO queue support
│   └── DeduplicationTracker.java  # Prevents duplicate processing
│
└── util/                          # Utilities
    ├── VersionUtil.java           # Connector version info
    └── MessageDecompressor.java   # Compression/decompression helpers
```

### What Each Package Does

**`aws/`**: Direct AWS SDK interaction
→ Handles authentication, API calls, error handling

**`config/`**: Configuration parsing and validation
→ Single source of truth for all settings

**`converter/`**: Message transformation pipeline
→ Decorator pattern allows chaining (decompress → avro → extract)

**`retry/`**: Failure handling
→ Exponential backoff, jitter, DLQ routing

**`filter/`**: Pre-processing filtering
→ Reduces unnecessary message processing

**`fifo/`**: FIFO-specific features
→ Ordering preservation, deduplication

**`util/`**: Shared utilities
→ Compression, version info

---

## Debugging Tips

### Enable Debug Logging

**In `logback.xml`:**
```xml
<logger name="io.connect.sqs" level="DEBUG"/>
```

**Or via environment variable:**
```bash
export CONNECT_LOG_LEVEL=DEBUG
```

### Common Issues & Where to Look

**Messages not appearing in Kafka:**
1. Check `SqsSourceTask.poll()` - Are messages received from SQS?
2. Check `MessageFilterProcessor` - Are they filtered out?
3. Check `RetryManager` - Are they in backoff?
4. Check logs for conversion errors

**AWS authentication failing:**
1. Check `SqsClient.createCredentialsProvider()` - Which method is used?
2. Enable AWS SDK debug logging: `-Daws.logLevel=DEBUG`
3. Check IAM permissions on the queue

**Retries not working:**
1. Check `RetryManager.recordFailure()` - Is retry count incrementing?
2. Check `calculateBackoffWithJitter()` - Is backoff calculated correctly?
3. Check `SqsSourceTask.poll()` - Are messages skipped during backoff?

**FIFO ordering issues:**
1. Check `kafka.topic.partition` config - Must route to same partition
2. Check MessageGroupId is used as Kafka key
3. Verify `tasks.max=1` (FIFO requires single task per queue)

### Useful Breakpoints

**To trace a message:**
1. `SqsSourceTask.poll():122` - Message received from SQS
2. `MessageConverter.convert()` - Transformation
3. `SqsSourceTask.commitRecord():390` - After Kafka write

**To debug retries:**
1. `RetryManager.recordFailure():61` - Failure recorded
2. `RetryManager.calculateBackoffWithJitter():91` - Backoff calculation
3. `SqsSourceTask.poll():185` - Retry check

**To debug filtering:**
1. `MessageFilterProcessor.filter():80` - Filter policy evaluation

---

## Next Steps

### After Understanding the Basics

1. **Read the full README.md** - Comprehensive feature documentation
2. **Review CONTRIBUTING.md** - Code standards, PR process
3. **Check the examples/** - Real-world configuration examples
4. **Read docs/** - Deep dives on specific features:
   - `SCHEMA_REGISTRY.md` - Avro/Protobuf/JSON Schema
   - `MESSAGE_DECOMPRESSION.md` - Compression handling
   - `CLAIM_CHECK_PATTERN.md` - Large message handling via S3

### Understanding Kafka Connect

If you're new to Kafka Connect:
- [Official Kafka Connect Docs](https://kafka.apache.org/documentation/#connect)
- [Confluent Connect Tutorial](https://docs.confluent.io/platform/current/connect/index.html)

### Questions?

- Check existing [GitHub Issues](https://github.com/next-govejero/kafka-connect-sqs-source-connector/issues)
- Review test files - they show usage patterns
- Read Javadoc comments - detailed method-level docs

---

**Happy Coding!** 🚀

If you found this guide helpful, consider giving the project a ⭐ on GitHub!
