# Performance Benchmarking Guide

This guide provides methodologies, tools, and baseline metrics for benchmarking the Kafka Connect SQS Source Connector in various scenarios.

## Table of Contents

- [Benchmark Overview](#benchmark-overview)
- [Test Environment Setup](#test-environment-setup)
- [Baseline Metrics](#baseline-metrics)
- [Benchmark Scenarios](#benchmark-scenarios)
- [Performance Analysis](#performance-analysis)
- [Optimization Recommendations](#optimization-recommendations)

## Benchmark Overview

### Goals

1. **Throughput**: Maximum messages per second
2. **Latency**: Time from SQS to Kafka (p50, p95, p99)
3. **Resource Utilization**: CPU, memory, network
4. **Scalability**: Performance with increasing load
5. **Reliability**: Behavior under failure scenarios

### Key Performance Indicators (KPIs)

| KPI | Target | Measurement |
|-----|--------|-------------|
| **Throughput** | > 1000 msg/s per task | Messages delivered to Kafka per second |
| **Latency (p50)** | < 500ms | Median end-to-end latency |
| **Latency (p95)** | < 2000ms | 95th percentile latency |
| **Latency (p99)** | < 5000ms | 99th percentile latency |
| **CPU Utilization** | < 70% | Average CPU usage under load |
| **Memory Usage** | < 80% heap | JVM heap utilization |
| **Error Rate** | < 0.1% | Failed messages / total messages |
| **Recovery Time** | < 30s | Time to resume after failure |

## Test Environment Setup

### Infrastructure Requirements

**Kafka Cluster:**
```yaml
Brokers: 3
Version: 3.6.1
Replication Factor: 3
Partitions: 12 (for test topic)
Instance Type: m5.xlarge (4 vCPU, 16 GB RAM)
```

**Kafka Connect:**
```yaml
Workers: 3
Version: 3.6.1
Instance Type: m5.large (2 vCPU, 8 GB RAM)
Heap Size: -Xms4G -Xmx4G
GC: G1GC
```

**AWS SQS:**
```yaml
Queue Type: Standard
Region: us-east-1
Visibility Timeout: 300s
Message Retention: 4 days
Long Polling: 20s
```

**Load Generator:**
```yaml
Tool: Custom Python script or AWS Lambda
Instance: m5.large
Message Rate: Configurable (100-10000 msg/s)
Message Size: Configurable (1KB-256KB)
```

### Docker Compose Test Environment

```yaml
version: '3.8'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
      KAFKA_NUM_PARTITIONS: 12

  localstack:
    image: localstack/localstack:latest
    environment:
      SERVICES: sqs,s3
      DEBUG: 1

  kafka-connect:
    image: kafka-connect-sqs:test
    depends_on:
      - kafka
      - localstack
    environment:
      CONNECT_BOOTSTRAP_SERVERS: kafka:9092
      CONNECT_REST_PORT: 8083
      KAFKA_HEAP_OPTS: "-Xms2G -Xmx2G"

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
    ports:
      - "9090:9090"

  grafana:
    image: grafana/grafana:latest
    ports:
      - "3000:3000"
```

### Monitoring Configuration

**prometheus.yml:**
```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'kafka-connect'
    static_configs:
      - targets: ['kafka-connect:8083']
    metrics_path: '/metrics'

  - job_name: 'kafka'
    static_configs:
      - targets: ['kafka:9092']
```

## Baseline Metrics

### Test 1: Single Task, Standard Queue

**Configuration:**
```properties
name=sqs-source-test1
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/test-queue
sqs.max.messages=10
sqs.wait.time.seconds=20
sqs.visibility.timeout.seconds=300
kafka.topic=test-topic
message.converter.class=io.connect.sqs.converter.DefaultMessageConverter
```

**Load:**
- Message Rate: 500 msg/s
- Message Size: 10 KB
- Duration: 10 minutes
- Total Messages: 300,000

**Expected Results:**
| Metric | Value |
|--------|-------|
| Throughput | 450-500 msg/s |
| Latency (p50) | 200-400ms |
| Latency (p95) | 800-1200ms |
| Latency (p99) | 1500-2500ms |
| CPU Usage | 40-60% |
| Memory Usage | 2-3 GB heap |
| Error Rate | 0% |

### Test 2: Multi-Task, Multi-Queue

**Configuration:**
```properties
name=sqs-source-test2
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=4
sqs.queue.urls=https://sqs.us-east-1.amazonaws.com/123456789012/queue1,\
  https://sqs.us-east-1.amazonaws.com/123456789012/queue2,\
  https://sqs.us-east-1.amazonaws.com/123456789012/queue3,\
  https://sqs.us-east-1.amazonaws.com/123456789012/queue4
sqs.max.messages=10
sqs.wait.time.seconds=20
kafka.topic=test-topic
message.converter.class=io.connect.sqs.converter.DefaultMessageConverter
```

**Load:**
- Message Rate: 2000 msg/s (500 per queue)
- Message Size: 10 KB
- Duration: 10 minutes
- Total Messages: 1,200,000

**Expected Results:**
| Metric | Value |
|--------|-------|
| Throughput | 1800-2000 msg/s |
| Latency (p50) | 300-500ms |
| Latency (p95) | 1000-1500ms |
| Latency (p99) | 2000-3000ms |
| CPU Usage | 60-80% |
| Memory Usage | 4-6 GB heap |
| Error Rate | 0% |

### Test 3: Large Messages with Claim Check

**Configuration:**
```properties
name=sqs-source-test3
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=2
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/large-queue
sqs.max.messages=5
message.converter.class=io.connect.sqs.converter.ClaimCheckMessageConverter
message.converter.delegate.class=io.connect.sqs.converter.DefaultMessageConverter
claim.check.field.paths=s3Uri
claim.check.retrieve.from.s3=true
aws.s3.region=us-east-1
```

**Load:**
- Message Rate: 100 msg/s
- SQS Message Size: 1 KB (S3 URI reference)
- S3 Object Size: 5 MB
- Duration: 10 minutes
- Total Messages: 60,000
- Total Data: 300 GB

**Expected Results:**
| Metric | Value |
|--------|-------|
| Throughput | 80-100 msg/s |
| Latency (p50) | 1000-1500ms |
| Latency (p95) | 2500-3500ms |
| Latency (p99) | 4000-6000ms |
| CPU Usage | 30-50% |
| Memory Usage | 3-4 GB heap |
| Network Bandwidth | 40-50 MB/s |
| Error Rate | 0% |

### Test 4: Schema Registry Validation

**Configuration:**
```properties
name=sqs-source-test4
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/avro-queue
sqs.max.messages=10
message.converter.class=io.connect.sqs.converter.AvroMessageConverter
schema.registry.url=http://schema-registry:8081
```

**Load:**
- Message Rate: 300 msg/s
- Message Size: 5 KB (Avro)
- Duration: 10 minutes
- Total Messages: 180,000

**Expected Results:**
| Metric | Value |
|--------|-------|
| Throughput | 250-300 msg/s |
| Latency (p50) | 400-600ms |
| Latency (p95) | 1200-1800ms |
| Latency (p99) | 2500-3500ms |
| CPU Usage | 50-70% |
| Memory Usage | 2.5-3.5 GB heap |
| Error Rate | 0% |

### Test 5: Compression + Decompression

**Configuration:**
```properties
name=sqs-source-test5
connector.class=io.connect.sqs.SqsSourceConnector
tasks.max=1
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/compressed-queue
sqs.max.messages=10
message.converter.class=io.connect.sqs.converter.DecompressingMessageConverter
message.decompression.delegate.converter.class=io.connect.sqs.converter.DefaultMessageConverter
message.decompression.format=AUTO
message.decompression.base64.decode=true
```

**Load:**
- Message Rate: 500 msg/s
- Compressed Size: 2 KB
- Decompressed Size: 20 KB
- Compression Ratio: 10:1
- Duration: 10 minutes
- Total Messages: 300,000

**Expected Results:**
| Metric | Value |
|--------|-------|
| Throughput | 400-500 msg/s |
| Latency (p50) | 400-700ms |
| Latency (p95) | 1200-2000ms |
| Latency (p99) | 2500-4000ms |
| CPU Usage | 60-80% (decompression overhead) |
| Memory Usage | 3-4 GB heap |
| Error Rate | 0% |

## Benchmark Scenarios

### Scenario 1: Burst Load

**Objective:** Test connector behavior under sudden traffic spikes

**Setup:**
1. Baseline: 100 msg/s
2. Burst: 5000 msg/s for 2 minutes
3. Return to baseline: 100 msg/s

**Metrics to Track:**
- Queue depth during burst
- Latency increase during burst
- Recovery time after burst
- Message loss (should be 0)

**Success Criteria:**
- No message loss
- Queue clears within 5 minutes after burst
- Latency returns to baseline within 2 minutes
- No task failures

### Scenario 2: Network Latency

**Objective:** Test performance with increased network latency

**Setup:**
1. Introduce artificial latency: 100ms, 200ms, 500ms
2. Measure impact on throughput and latency

**Tools:**
```bash
# Using tc (traffic control) to add latency
tc qdisc add dev eth0 root netem delay 100ms
```

**Expected Impact:**
| Network Latency | Throughput Impact | End-to-End Latency |
|-----------------|-------------------|--------------------|
| 0ms (baseline) | 100% | 400ms |
| 100ms | 80-90% | 600ms |
| 200ms | 60-70% | 800ms |
| 500ms | 30-40% | 1400ms |

### Scenario 3: Partial Failures

**Objective:** Test resilience and recovery

**Failure Scenarios:**
1. **Kafka Broker Failure**
   - Kill one Kafka broker
   - Measure recovery time
   - Verify no message loss

2. **Task Failure**
   - Kill specific connector task
   - Measure rebalance time
   - Verify message reprocessing

3. **SQS Throttling**
   - Simulate SQS rate limiting
   - Verify retry behavior
   - Measure impact on throughput

**Success Criteria:**
- Automatic recovery within 30 seconds
- No message loss
- No duplicate messages (for FIFO queues)

### Scenario 4: FIFO Queue Performance

**Objective:** Compare FIFO vs Standard queue performance

**Configuration:**
```properties
# FIFO Queue
sqs.queue.url=https://sqs.us-east-1.amazonaws.com/123456789012/test.fifo
sqs.max.messages=3  # Lower for ordering
fifo.deduplication.enabled=true
fifo.deduplication.time.window.minutes=5
```

**Expected Results:**
| Queue Type | Max Throughput | Latency (p50) | Deduplication |
|------------|---------------|---------------|---------------|
| Standard | 2000+ msg/s | 300ms | N/A |
| FIFO | 300 msg/s | 500ms | Active |
| FIFO (batching) | 900 msg/s | 700ms | Active |

### Scenario 5: Resource Starvation

**Objective:** Test behavior under resource constraints

**Test Cases:**
1. **Limited CPU** (50% throttling)
2. **Limited Memory** (2GB heap for 4 tasks)
3. **Limited Network** (10 Mbps cap)

**Tools:**
```bash
# CPU limitation (Docker)
docker update --cpus="0.5" kafka-connect

# Memory limitation
docker update --memory="2g" kafka-connect

# Network limitation (tc)
tc qdisc add dev eth0 root tbf rate 10mbit burst 32kbit latency 400ms
```

**Expected Behavior:**
- Graceful degradation (no crashes)
- Reduced throughput proportional to resource constraint
- Error handling and retry mechanisms engaged

## Performance Analysis

### Data Collection

**JVM Metrics:**
```bash
# Heap usage
jstat -gc <pid> 1000 100

# Thread dump
jstack <pid> > threaddump.txt

# Heap dump
jmap -dump:format=b,file=heap.bin <pid>
```

**Connector Metrics:**
```bash
# Get connector metrics via JMX
curl http://kafka-connect:8083/connectors/sqs-source/status | jq .

# Kafka Connect REST API
curl http://kafka-connect:8083/connectors/sqs-source/tasks/0/status
```

**AWS CloudWatch Metrics:**
```bash
# SQS metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/SQS \
  --metric-name ApproximateNumberOfMessagesVisible \
  --dimensions Name=QueueName,Value=test-queue \
  --start-time 2024-01-01T00:00:00Z \
  --end-time 2024-01-01T01:00:00Z \
  --period 300 \
  --statistics Average,Maximum
```

### Bottleneck Identification

**Common Bottlenecks:**

1. **SQS Receive Rate Limiting**
   - Symptom: Consistent throughput below expected
   - Diagnosis: CloudWatch ThrottledRequests metric > 0
   - Solution: Implement exponential backoff, increase batch size

2. **Kafka Producer Throughput**
   - Symptom: High producer latency
   - Diagnosis: Kafka producer metrics show buffering
   - Solution: Increase `buffer.memory`, tune `batch.size` and `linger.ms`

3. **CPU Saturation**
   - Symptom: High CPU usage (>90%)
   - Diagnosis: Thread contention, inefficient processing
   - Solution: Scale horizontally, optimize converters

4. **Memory Pressure**
   - Symptom: Frequent garbage collection, high heap usage
   - Diagnosis: Heap dumps show retained objects
   - Solution: Increase heap size, reduce batch sizes

5. **Network Bandwidth**
   - Symptom: Network interface saturation
   - Diagnosis: `ifstat` shows max bandwidth usage
   - Solution: Compression, claim check pattern, faster network

### Profiling Tools

**Java Flight Recorder (JFR):**
```bash
# Start recording
jcmd <pid> JFR.start duration=60s filename=recording.jfr

# Analyze with JMC (Java Mission Control)
jmc recording.jfr
```

**Async Profiler:**
```bash
# CPU profiling
./profiler.sh -d 60 -f cpu.html <pid>

# Allocation profiling
./profiler.sh -d 60 -e alloc -f alloc.html <pid>
```

**Visual VM:**
```bash
# Connect to running JVM
visualvm --openpid <pid>
```

## Optimization Recommendations

### Configuration Tuning

**High Throughput:**
```properties
# Maximize parallelism
tasks.max=8

# Larger batches
sqs.max.messages=10

# Long polling
sqs.wait.time.seconds=20

# Kafka producer tuning
producer.batch.size=32768
producer.linger.ms=10
producer.compression.type=snappy
producer.buffer.memory=67108864
```

**Low Latency:**
```properties
# Smaller batches
sqs.max.messages=1

# Shorter visibility timeout
sqs.visibility.timeout.seconds=30

# Kafka producer tuning
producer.batch.size=0
producer.linger.ms=0
producer.acks=1  # Trade durability for speed
```

**High Reliability:**
```properties
# Enable DLQ
dlq.topic=sqs-dlq

# Conservative retry settings
retry.backoff.ms=2000
max.retries=5

# Kafka producer tuning
producer.acks=all
producer.enable.idempotence=true
producer.max.in.flight.requests.per.connection=1
```

### JVM Tuning

**Large Heap (8GB+):**
```bash
export KAFKA_HEAP_OPTS="-Xms8G -Xmx8G"
export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC \
  -XX:MaxGCPauseMillis=20 \
  -XX:InitiatingHeapOccupancyPercent=35 \
  -XX:G1ReservePercent=20"
```

**Small Heap (2GB):**
```bash
export KAFKA_HEAP_OPTS="-Xms2G -Xmx2G"
export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC \
  -XX:MaxGCPauseMillis=10"
```

### Architecture Optimization

**Horizontal Scaling:**
```
1 queue, 1 task: 500 msg/s
4 queues, 4 tasks: 2000 msg/s (linear scaling)
8 queues, 8 tasks: 4000 msg/s (linear scaling)
```

**Message Size Optimization:**
- Use claim check for messages > 50KB
- Compress payloads (10:1 compression ratio typical)
- Use Avro/Protobuf for compact serialization

**Network Optimization:**
- VPC endpoints (no internet latency)
- Same region for SQS, S3, Kafka
- Enhanced networking (SR-IOV) for EC2

## Benchmarking Tools

### Load Generator Script

**Python Load Generator:**
```python
#!/usr/bin/env python3
import boto3
import time
import json
from concurrent.futures import ThreadPoolExecutor

def send_messages(queue_url, rate, duration, message_size):
    sqs = boto3.client('sqs', region_name='us-east-1')
    message_body = 'x' * message_size

    start_time = time.time()
    sent = 0

    with ThreadPoolExecutor(max_workers=10) as executor:
        while time.time() - start_time < duration:
            batch_start = time.time()

            # Send messages
            for _ in range(rate):
                executor.submit(sqs.send_message,
                    QueueUrl=queue_url,
                    MessageBody=json.dumps({
                        "data": message_body,
                        "timestamp": time.time()
                    })
                )
                sent += 1

            # Rate limiting
            elapsed = time.time() - batch_start
            if elapsed < 1:
                time.sleep(1 - elapsed)

    print(f"Sent {sent} messages in {duration} seconds")
    print(f"Average rate: {sent/duration} msg/s")

if __name__ == "__main__":
    send_messages(
        queue_url="https://sqs.us-east-1.amazonaws.com/123456789012/test",
        rate=500,  # messages per second
        duration=600,  # 10 minutes
        message_size=10000  # 10KB
    )
```

### Latency Measurement

**Timestamp Injection:**
```python
# Producer: Add timestamp to message
message = {
    "data": payload,
    "producer_timestamp": time.time()
}

# Consumer: Calculate latency
consumer_timestamp = time.time()
latency_ms = (consumer_timestamp - message['producer_timestamp']) * 1000
```

**CloudWatch Insights Query:**
```
fields @timestamp, @message
| filter @message like /Latency:/
| parse @message "Latency: * ms" as latency
| stats avg(latency) as avg_latency,
        pct(latency, 50) as p50,
        pct(latency, 95) as p95,
        pct(latency, 99) as p99
```

## Continuous Benchmarking

### CI/CD Integration

**.github/workflows/performance.yml:**
```yaml
name: Performance Benchmarks

on:
  schedule:
    - cron: '0 2 * * 0'  # Weekly on Sunday
  workflow_dispatch:

jobs:
  benchmark:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3

      - name: Set up Docker Compose
        run: docker-compose -f docker-compose-perf.yml up -d

      - name: Run benchmark suite
        run: |
          ./scripts/run-benchmarks.sh

      - name: Upload results
        uses: actions/upload-artifact@v3
        with:
          name: benchmark-results
          path: benchmark-results.json

      - name: Compare with baseline
        run: ./scripts/compare-benchmarks.sh
```

### Performance Regression Detection

**Automated Alerting:**
```python
# Compare current results with baseline
if current_throughput < baseline_throughput * 0.9:
    alert("Performance regression: Throughput dropped by 10%")

if current_p95_latency > baseline_p95_latency * 1.2:
    alert("Performance regression: P95 latency increased by 20%")
```

## Summary

This benchmarking guide provides:
- ✅ Standardized test scenarios
- ✅ Baseline performance metrics
- ✅ Tools and scripts for load testing
- ✅ Analysis methodologies
- ✅ Optimization recommendations
- ✅ Continuous performance monitoring

Use these benchmarks to:
1. Validate performance before production deployment
2. Detect performance regressions in CI/CD
3. Capacity planning for expected load
4. Troubleshoot performance issues
5. Optimize configuration for your use case

## Next Steps

1. **Run Baseline Tests**: Execute tests 1-5 in your environment
2. **Establish Baselines**: Document results for future comparison
3. **Scenario Testing**: Run burst, failure, and scaling tests
4. **Optimize**: Apply recommendations based on results
5. **Monitor**: Set up continuous performance monitoring
6. **Iterate**: Regular benchmarking and optimization

For production operations, see [PRODUCTION_OPERATIONS.md](PRODUCTION_OPERATIONS.md).
