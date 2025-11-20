# Production Operations Guide

This guide covers operational best practices, monitoring, troubleshooting, and maintenance for running the Kafka Connect SQS Source Connector in production environments.

## Table of Contents

- [Pre-Production Checklist](#pre-production-checklist)
- [Capacity Planning](#capacity-planning)
- [Monitoring & Observability](#monitoring--observability)
- [Performance Tuning](#performance-tuning)
- [High Availability](#high-availability)
- [Disaster Recovery](#disaster-recovery)
- [Common Issues](#common-issues)
- [Maintenance](#maintenance)
- [Cost Optimization](#cost-optimization)

## Pre-Production Checklist

### Security

- [ ] SCRAM-SHA-512 authentication configured for Kafka
- [ ] AWS IAM policies follow least privilege principle
- [ ] Credentials stored in secret manager (not hardcoded)
- [ ] TLS/SSL enabled for Kafka connections (`security.protocol=SASL_SSL`)
- [ ] VPC endpoints configured for SQS access
- [ ] Security groups properly configured
- [ ] CloudTrail logging enabled for AWS API calls
- [ ] Regular credential rotation policy in place

### Configuration

- [ ] Dead Letter Queue (DLQ) configured and monitored
- [ ] Retry logic configured with appropriate max retries (3-5 recommended)
- [ ] Visibility timeout matches processing time (recommendation: 5x average processing time)
- [ ] Message deletion enabled after successful processing
- [ ] Appropriate batch size configured (start with 5 for standard, 1-3 for FIFO)
- [ ] Long polling enabled (wait time > 0, recommend 10-20 seconds)
- [ ] Schema Registry endpoints validated and accessible
- [ ] Message filtering configured if needed

### Infrastructure

- [ ] Kafka Connect cluster properly sized (see [Capacity Planning](#capacity-planning))
- [ ] Adequate CPU and memory allocated
- [ ] Network bandwidth sufficient for message throughput
- [ ] Auto-scaling configured (for container orchestration)
- [ ] Load balancers configured (for Kafka Connect REST API)
- [ ] Backup and recovery procedures documented

### Monitoring

- [ ] Kafka Connect metrics collection configured
- [ ] AWS CloudWatch metrics enabled for SQS
- [ ] Log aggregation configured (CloudWatch Logs, ELK, etc.)
- [ ] Alerting rules configured for critical metrics
- [ ] Dashboard created for operational visibility
- [ ] On-call rotation and escalation policy established

## Capacity Planning

### Message Throughput Estimation

**Formula:**
```
Messages per second = (Tasks × Batch Size) / (Polling Interval + Processing Time)
```

**Example:**
- 4 tasks (4 queues)
- Batch size: 10 messages
- Polling interval: 1 second
- Average processing time: 2 seconds

```
Throughput = (4 × 10) / (1 + 2) = 13.3 messages/second ≈ 48,000 messages/hour
```

### Resource Requirements

#### CPU

**Per Task:**
- Baseline: 0.5 vCPU
- With compression: +0.3 vCPU
- With schema validation: +0.2 vCPU
- With claim check (S3): +0.1 vCPU

**Example for 4 tasks with all features:**
- 4 tasks × (0.5 + 0.3 + 0.2 + 0.1) = 4.4 vCPUs minimum
- Recommended: 6-8 vCPUs (for overhead and scaling)

#### Memory

**Per Task:**
- Baseline: 512 MB
- With message buffering: +256 MB per batch
- With schema registry: +256 MB
- JVM heap: 2× task memory

**Example for 4 tasks:**
- Task memory: 4 × (512 + 256 + 256) = 4 GB
- JVM heap: 8 GB
- Total container memory: 10 GB (including overhead)

#### Network Bandwidth

**Formula:**
```
Bandwidth = Messages/sec × Average Message Size × 2 (SQS receive + Kafka send)
```

**Example:**
- 100 messages/sec
- Average size: 50 KB
- Required: 100 × 50 KB × 2 = 10 MB/s ≈ 80 Mbps

**Recommendation:** Provision 2-3× estimated bandwidth for spikes

### Queue Configuration

#### Standard Queues

- **Batch Size**: 5-10 messages
- **Visibility Timeout**: 5× average processing time (min 30s, max 300s)
- **Wait Time**: 10-20 seconds (long polling)
- **Message Retention**: 4-7 days
- **Max Message Size**: 256 KB (use claim check for larger)

#### FIFO Queues

- **Batch Size**: 1-3 messages (for ordering)
- **Visibility Timeout**: Same as standard
- **Wait Time**: 10-20 seconds
- **Deduplication**: 5-minute window configured in connector
- **Throughput**: Up to 300 messages/sec per queue (3000 with batching)

## Monitoring & Observability

### Key Metrics to Track

#### Connector Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `messages-received` | Total messages received from SQS | Trend monitoring |
| `messages-sent` | Messages successfully sent to Kafka | Should match received |
| `messages-failed` | Messages sent to DLQ | > 1% of received |
| `messages-deleted` | Messages deleted from SQS | Should match sent |
| `messages-retried` | Messages retried before success | > 5% of received |
| `messages-deduplicated` | FIFO duplicates detected | Normal for FIFO |
| `task-failures` | Task restart count | > 3 per hour |
| `processing-time-ms` | Average message processing time | > 5000ms (5s) |

#### AWS CloudWatch Metrics (SQS)

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `ApproximateNumberOfMessagesVisible` | Messages in queue | > 10,000 (backlog) |
| `ApproximateAgeOfOldestMessage` | Age of oldest message | > 300s (5 min) |
| `NumberOfMessagesSent` | Messages added to queue | Trend monitoring |
| `NumberOfMessagesReceived` | Messages consumed | Should match sent |
| `NumberOfMessagesDeleted` | Confirmed deletions | Should match received |
| `ApproximateNumberOfMessagesNotVisible` | In-flight messages | > batch size × tasks |

#### Kafka Metrics

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| `source-record-poll-total` | Records polled from source | Trend monitoring |
| `source-record-write-total` | Records written to Kafka | Should match poll |
| `source-record-write-rate` | Write rate (records/sec) | Capacity planning |
| `source-record-active-count` | Active records being processed | < batch size × tasks |

### Log Monitoring

**Critical Log Patterns to Alert On:**

```regex
# Authentication failures
ERROR.*Authentication failed|SASL authentication failed

# AWS credential issues
ERROR.*Unable to load credentials|ExpiredTokenException

# SQS receive failures
ERROR.*Failed to receive messages from SQS|ReceiveMessage failed

# Kafka producer failures
ERROR.*Failed to send record|TimeoutException

# Schema Registry failures
ERROR.*Schema Registry|Failed to fetch schema

# Out of memory
ERROR.*OutOfMemoryError|GC overhead limit exceeded

# Connection timeouts
ERROR.*SocketTimeoutException|Connection timeout
```

### Sample Prometheus Queries

```promql
# Message processing rate
rate(kafka_connect_source_task_messages_sent_total[5m])

# Error rate
rate(kafka_connect_source_task_messages_failed_total[5m]) /
rate(kafka_connect_source_task_messages_received_total[5m]) * 100

# Queue backlog
aws_sqs_approximate_number_of_messages_visible

# Processing latency (95th percentile)
histogram_quantile(0.95, rate(kafka_connect_source_task_processing_time_ms_bucket[5m]))
```

### Sample Grafana Dashboard

Create a dashboard with these panels:

1. **Message Flow**
   - Messages received (SQS)
   - Messages sent (Kafka)
   - Messages failed (DLQ)
   - Messages deleted (SQS)

2. **Performance**
   - Processing latency (p50, p95, p99)
   - Throughput (messages/sec)
   - Batch processing time

3. **Health**
   - Task status (running/failed)
   - Error rate
   - Retry rate
   - Deduplication rate

4. **Infrastructure**
   - CPU utilization
   - Memory usage
   - Network throughput
   - JVM heap usage

5. **AWS Resources**
   - SQS queue depth
   - SQS age of oldest message
   - S3 request count (for claim check)

## Performance Tuning

### Optimize Throughput

1. **Increase Parallelism**
   ```properties
   tasks.max=8  # Increase number of tasks
   ```
   - One task per queue for multi-queue setup
   - Multiple tasks for single queue (limited benefit)

2. **Batch Processing**
   ```properties
   sqs.max.messages=10  # Max batch size
   ```
   - Larger batches = higher throughput but higher latency
   - Standard queues: 5-10 messages
   - FIFO queues: 1-3 messages (for ordering)

3. **Long Polling**
   ```properties
   sqs.wait.time.seconds=20  # Maximum long polling
   ```
   - Reduces empty receive calls
   - Improves efficiency and reduces costs

4. **Reduce Processing Time**
   ```properties
   # Disable unnecessary features
   message.filtering.enabled=false  # If not needed

   # Use simpler converters if possible
   message.converter.class=io.connect.sqs.converter.DefaultMessageConverter
   ```

### Optimize Latency

1. **Reduce Batch Size**
   ```properties
   sqs.max.messages=1  # Process immediately
   ```

2. **Decrease Visibility Timeout**
   ```properties
   sqs.visibility.timeout.seconds=30  # Faster retry on failure
   ```

3. **Disable Decompression if Not Needed**
   ```properties
   # Use direct converter
   message.converter.class=io.connect.sqs.converter.AvroMessageConverter
   ```

### Memory Optimization

1. **JVM Tuning**
   ```bash
   # Set appropriate heap size
   export KAFKA_HEAP_OPTS="-Xms4G -Xmx8G"

   # Use G1GC for large heaps
   export KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseG1GC -XX:MaxGCPauseMillis=20"
   ```

2. **Limit Batch Sizes**
   ```properties
   # Smaller batches = less memory per task
   sqs.max.messages=5
   ```

3. **Configure Message Size Limits**
   ```properties
   # Use claim check for large messages
   claim.check.enabled=true
   claim.check.field.paths=s3Uri,body.location
   ```

## High Availability

### Multi-AZ Deployment

**AWS ECS Fargate Example:**

```json
{
  "serviceName": "kafka-connect-sqs",
  "taskDefinition": "kafka-connect-sqs:latest",
  "desiredCount": 3,
  "launchType": "FARGATE",
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": [
        "subnet-1a",
        "subnet-1b",
        "subnet-1c"
      ],
      "securityGroups": ["sg-kafka-connect"],
      "assignPublicIp": "DISABLED"
    }
  },
  "placementConstraints": [
    {
      "type": "distinctInstance"
    }
  ]
}
```

### Kafka Connect Cluster

**Distributed Mode Configuration:**

```properties
# Connect cluster configuration
group.id=sqs-connect-cluster
config.storage.topic=sqs-connect-configs
offset.storage.topic=sqs-connect-offsets
status.storage.topic=sqs-connect-status

# Replication for fault tolerance
config.storage.replication.factor=3
offset.storage.replication.factor=3
status.storage.replication.factor=3

# Session timeout
session.timeout.ms=30000
heartbeat.interval.ms=3000
```

### Failover Handling

1. **Task Rebalancing**
   - Automatic when tasks fail or workers leave cluster
   - Configure appropriate timeouts

2. **SQS Visibility Timeout**
   - Messages become visible again after timeout
   - Ensures no message loss during failover
   - Set to 5× average processing time

3. **Kafka Connect Offset Storage**
   - Tracks SQS message deletion state
   - Prevents duplicate processing after restart

## Disaster Recovery

### Backup Strategy

1. **Connector Configuration**
   ```bash
   # Backup connector config
   curl http://kafka-connect:8083/connectors/sqs-source/config > sqs-source-config.json

   # Store in version control
   git add sqs-source-config.json && git commit -m "Backup connector config"
   ```

2. **Kafka Connect Internal Topics**
   - Ensure proper replication factor (3+)
   - Regular backups of offset topics
   - Document restoration procedure

3. **SQS Message Retention**
   - Set message retention to 7+ days
   - Allows replay in case of data loss
   - Consider DLQ message archival to S3

### Recovery Procedures

**Scenario 1: Single Task Failure**
```bash
# Check task status
curl http://kafka-connect:8083/connectors/sqs-source/status

# Restart specific task
curl -X POST http://kafka-connect:8083/connectors/sqs-source/tasks/0/restart
```

**Scenario 2: Connector Failure**
```bash
# Restart connector
curl -X POST http://kafka-connect:8083/connectors/sqs-source/restart

# If restart fails, delete and recreate
curl -X DELETE http://kafka-connect:8083/connectors/sqs-source
curl -X POST -H "Content-Type: application/json" \
  --data @sqs-source-config.json \
  http://kafka-connect:8083/connectors
```

**Scenario 3: Complete Cluster Failure**
```bash
# 1. Restore Kafka Connect cluster
# 2. Verify internal topics exist
kafka-topics --bootstrap-server kafka:9092 --list | grep connect

# 3. Restore connector configuration
curl -X POST -H "Content-Type: application/json" \
  --data @sqs-source-config.json \
  http://kafka-connect:8083/connectors

# 4. Verify tasks are running
curl http://kafka-connect:8083/connectors/sqs-source/status
```

**Scenario 4: Data Loss (Replay from SQS)**
```bash
# 1. Stop connector
curl -X PUT http://kafka-connect:8083/connectors/sqs-source/pause

# 2. If messages still in SQS (within retention), they will be reprocessed
# 3. If messages purged, restore from DLQ or S3 archive (if configured)

# 4. Resume connector
curl -X PUT http://kafka-connect:8083/connectors/sqs-source/resume
```

## Common Issues

### Issue: High Message Latency

**Symptoms:**
- SQS messages take too long to reach Kafka
- `ApproximateAgeOfOldestMessage` increasing

**Diagnosis:**
```bash
# Check queue depth
aws sqs get-queue-attributes \
  --queue-url https://sqs.region.amazonaws.com/account/queue \
  --attribute-names ApproximateNumberOfMessagesVisible

# Check connector throughput
curl http://kafka-connect:8083/connectors/sqs-source/status
```

**Solutions:**
1. Increase task count: `tasks.max=8`
2. Increase batch size: `sqs.max.messages=10`
3. Verify network bandwidth is sufficient
4. Check Kafka producer performance

### Issue: Message Duplication

**Symptoms:**
- Same message appears multiple times in Kafka
- For FIFO queues: `messages-deduplicated` counter not increasing

**Diagnosis:**
```bash
# Check deduplication configuration
curl http://kafka-connect:8083/connectors/sqs-source/config | grep deduplication
```

**Solutions:**
1. Verify FIFO queue configuration
2. Check deduplication window: `fifo.deduplication.time.window.minutes=5`
3. Ensure message group IDs are present
4. Implement idempotent consumers in downstream applications

### Issue: Out of Memory

**Symptoms:**
- Task failures with `OutOfMemoryError`
- High JVM heap usage
- Frequent garbage collection

**Diagnosis:**
```bash
# Check JVM metrics
jmap -heap <pid>

# Check heap dump
jmap -dump:format=b,file=heap.bin <pid>
```

**Solutions:**
1. Increase heap size: `-Xmx8G`
2. Reduce batch size: `sqs.max.messages=5`
3. Disable unnecessary converters
4. Use claim check pattern for large messages
5. Tune GC: `-XX:+UseG1GC`

### Issue: Schema Registry Errors

**Symptoms:**
- `Failed to fetch schema` errors
- Schema incompatibility errors

**Diagnosis:**
```bash
# Check schema exists
curl http://schema-registry:8081/subjects/

# Check schema version
curl http://schema-registry:8081/subjects/topic-value/versions/latest
```

**Solutions:**
1. Verify Schema Registry connectivity
2. Check authentication credentials
3. Ensure schema is registered
4. Validate schema compatibility mode
5. Review schema evolution changes

### Issue: AWS Credential Expiration

**Symptoms:**
- `ExpiredTokenException`
- Authentication failures after period of time

**Diagnosis:**
```bash
# Check credential expiration (for assumed roles)
aws sts get-caller-identity

# Check connector logs
docker logs kafka-connect | grep -i "expired\|credential"
```

**Solutions:**
1. Use IAM roles instead of access keys (ECS Task Roles, EC2 Instance Profiles)
2. Implement credential rotation
3. Increase STS session duration
4. Configure credential refresh in connector

## Maintenance

### Regular Tasks

**Daily:**
- [ ] Monitor DLQ for failed messages
- [ ] Check connector and task status
- [ ] Review error logs
- [ ] Verify message throughput

**Weekly:**
- [ ] Review performance metrics
- [ ] Check resource utilization trends
- [ ] Validate backup procedures
- [ ] Review cost metrics

**Monthly:**
- [ ] Update dependencies (check Dependabot)
- [ ] Review and update documentation
- [ ] Conduct failover drills
- [ ] Review and optimize configurations
- [ ] Audit AWS IAM permissions

**Quarterly:**
- [ ] Capacity planning review
- [ ] Security audit
- [ ] Disaster recovery testing
- [ ] Performance benchmarking
- [ ] Cost optimization review

### Upgrade Procedure

1. **Review Release Notes**
   ```bash
   # Check CHANGELOG.md for breaking changes
   cat CHANGELOG.md
   ```

2. **Test in Non-Production**
   ```bash
   # Deploy to staging environment
   # Run integration tests
   # Validate functionality
   ```

3. **Backup Current State**
   ```bash
   # Backup connector configuration
   curl http://kafka-connect:8083/connectors/sqs-source/config > backup-config.json
   ```

4. **Rolling Upgrade** (Distributed Mode)
   ```bash
   # Upgrade one worker at a time
   # Tasks will rebalance automatically
   # Monitor for issues
   ```

5. **Validate**
   ```bash
   # Check connector status
   curl http://kafka-connect:8083/connectors/sqs-source/status

   # Verify message flow
   # Check metrics
   ```

### Log Rotation

**Docker:**
```json
{
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "100m",
    "max-file": "10"
  }
}
```

**ECS:**
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/kafka-connect-sqs",
      "awslogs-region": "us-east-1",
      "awslogs-stream-prefix": "ecs",
      "awslogs-create-group": "true"
    }
  }
}
```

## Cost Optimization

### SQS Cost Reduction

1. **Long Polling**
   ```properties
   sqs.wait.time.seconds=20  # Reduces API call costs by 99%
   ```
   - Empty receive calls are still charged
   - Long polling reduces empty calls

2. **Batch Processing**
   ```properties
   sqs.max.messages=10  # Receive multiple messages per API call
   ```
   - 1 API call for 10 messages vs. 10 API calls for 10 messages

3. **Right-Size Message Retention**
   ```bash
   # Don't over-retain messages (default: 4 days)
   aws sqs set-queue-attributes \
     --queue-url $QUEUE_URL \
     --attributes MessageRetentionPeriod=345600  # 4 days
   ```

### Kafka Cost Reduction

1. **Message Compression**
   ```properties
   # Enable compression in Kafka producer
   compression.type=snappy
   ```

2. **Appropriate Replication**
   ```properties
   # Don't over-replicate non-critical topics
   # Use replication.factor=2 for DLQ (instead of 3)
   ```

### Infrastructure Cost Reduction

1. **Right-Size Instances**
   - Start small, scale based on metrics
   - Use autoscaling based on queue depth

2. **Use Spot Instances** (Non-critical workloads)
   ```json
   {
     "capacityProviderStrategy": [
       {
         "capacityProvider": "FARGATE_SPOT",
         "weight": 1
       }
     ]
   }
   ```

3. **Optimize Network Costs**
   - Use VPC endpoints (no data transfer charges)
   - Same-region SQS and Kafka

### Monitoring Costs

```bash
# Get SQS request metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/SQS \
  --metric-name NumberOfMessagesReceived \
  --dimensions Name=QueueName,Value=my-queue \
  --start-time 2024-01-01T00:00:00Z \
  --end-time 2024-01-31T23:59:59Z \
  --period 86400 \
  --statistics Sum

# Estimate monthly cost
# SQS: $0.40 per million requests (after free tier)
# Data transfer: $0.09 per GB out (to Kafka)
```

## Runbook Summary

Quick reference for common operations:

| Operation | Command |
|-----------|---------|
| Check status | `curl http://kafka-connect:8083/connectors/sqs-source/status` |
| Restart connector | `curl -X POST http://kafka-connect:8083/connectors/sqs-source/restart` |
| Restart task | `curl -X POST http://kafka-connect:8083/connectors/sqs-source/tasks/0/restart` |
| Pause connector | `curl -X PUT http://kafka-connect:8083/connectors/sqs-source/pause` |
| Resume connector | `curl -X PUT http://kafka-connect:8083/connectors/sqs-source/resume` |
| Delete connector | `curl -X DELETE http://kafka-connect:8083/connectors/sqs-source` |
| Get config | `curl http://kafka-connect:8083/connectors/sqs-source/config` |
| Check queue depth | `aws sqs get-queue-attributes --queue-url $URL --attribute-names All` |
| Purge queue | `aws sqs purge-queue --queue-url $URL` |

## Additional Resources

- [README.md](../README.md) - Getting started guide
- [CONTRIBUTING.md](../CONTRIBUTING.md) - Development guidelines
- [docs/ECS_DEPLOYMENT.md](ECS_DEPLOYMENT.md) - AWS ECS deployment
- [docs/SCHEMA_REGISTRY.md](SCHEMA_REGISTRY.md) - Schema Registry integration
- [docs/CLAIM_CHECK_PATTERN.md](CLAIM_CHECK_PATTERN.md) - Large message handling

## Support

For issues or questions:
- GitHub Issues: https://github.com/next-govejero/kafka-connect-sqs-source-connector/issues
- Security: See [SECURITY.md](../SECURITY.md)
