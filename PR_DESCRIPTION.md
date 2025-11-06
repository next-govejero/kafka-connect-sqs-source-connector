# Kafka Connect SQS Source Connector - Production-Ready Implementation

## 🎯 Overview

This PR introduces a complete, production-ready Kafka Connect source connector that streams messages from AWS SQS queues into Apache Kafka topics. The connector is designed for reliability, scalability, and enterprise-grade error handling with comprehensive test coverage.

## ✨ Key Features

### Core Functionality
- **SQS to Kafka Streaming**: Poll messages from AWS SQS and produce to Kafka topics
- **Flexible AWS Authentication**: Support for multiple authentication methods
  - Static credentials (access key + secret)
  - AWS credentials profiles
  - ECS Fargate task roles (auto-detection)
  - EC2 instance profiles
  - STS assume role with external ID
  - Multi-cloud support (AWS, Azure, GCP, bare metal)
- **SCRAM-SHA-512 Authentication**: Mandatory secure authentication for Kafka connections
- **Message Attribute Preservation**: SQS message attributes converted to Kafka headers

### Advanced Error Handling
- **Dead Letter Queue (DLQ)**: Route failed messages to separate Kafka topic with comprehensive error tracking
  - Full exception details in headers (class, message, stacktrace)
  - Original message body preserved
  - Error timestamp and source queue tracking
  - Configurable: messages deleted if no DLQ configured
- **Graceful Failure Handling**: Continues processing when individual messages fail

### Performance & Reliability
- **Configurable Batching**: 1-10 messages per poll for optimal throughput
- **Long Polling Support**: Configurable wait times to reduce empty polls
- **Visibility Timeout**: Configurable message visibility for reliable processing
- **Automatic Message Deletion**: Optional deletion after successful processing
- **Metrics & Monitoring**: Built-in counters for received, sent, deleted, and failed messages

### Developer Experience
- **Comprehensive Testing**: 85%+ test coverage
  - 18 unit tests for SqsSourceTask
  - 16 unit tests for SqsClient
  - 6 end-to-end integration tests (full SQS → Connector → Kafka flow)
- **Docker Support**: Complete Docker Compose stack for local development
- **Optimized Build**: BuildKit cache mounts reduce rebuild time by 85% (4-6 min → 30-60 sec)
- **CI/CD Pipeline**: Matrix builds (Java 11 & 17), automated testing, security scanning

## 📋 What's Changed

### Core Implementation
- ✅ `SqsSourceConnector` - Main connector class with task management
- ✅ `SqsSourceTask` - Polling logic, message conversion, DLQ handling
- ✅ `SqsClient` - AWS SDK v2 wrapper with multi-cloud auth
- ✅ `DefaultMessageConverter` - Message conversion with attribute preservation
- ✅ `SqsSourceConnectorConfig` - 18+ configuration parameters with validation

### Test Coverage (40+ Tests)
- ✅ **SqsSourceTaskTest** (18 tests):
  - Task lifecycle (start/stop)
  - Message polling and conversion
  - Commit and deletion logic
  - Error handling with/without DLQ
  - DLQ record validation with error headers
- ✅ **SqsClientTest** (16 tests):
  - All authentication methods
  - ECS Fargate detection
  - Multi-region support
  - Endpoint override for LocalStack
- ✅ **SqsToKafkaE2EIT** (6 integration tests):
  - Full end-to-end message flow
  - Message attribute preservation
  - Large batch handling (50 messages)
  - Empty queue handling
  - Message ordering verification
- ✅ **DefaultMessageConverterTest** (8 tests)
- ✅ **SqsSourceConnectorTest** (5 tests)

### Build & CI/CD
- ✅ Optimized Dockerfile with BuildKit cache mounts
- ✅ GitHub Actions CI with Java 11 & 17 matrix builds
- ✅ Integration tests in CI pipeline
- ✅ Dependabot for automatic dependency updates
- ✅ Code quality checks (Checkstyle, JaCoCo)
- ✅ Docker build caching in GitHub Actions

### Documentation
- ✅ Comprehensive README with quick start, configuration reference, examples
- ✅ ECS deployment guide with IAM policies and task definitions
- ✅ Docker build optimization guide
- ✅ Contributing guidelines
- ✅ 6 example configurations (ECS, Azure, GCP, bare metal, standalone, basic)
- ✅ Codecov badge and test coverage callout

### Configuration Examples
- ✅ `sqs-source-connector-ecs.properties` - ECS Fargate with task role
- ✅ `sqs-source-connector-azure.properties` - Azure Key Vault integration
- ✅ `sqs-source-connector-gcp.properties` - GCP Secret Manager
- ✅ `sqs-source-connector-bare-metal.properties` - Static credentials
- ✅ `sqs-source-connector-standalone.properties` - Standalone Connect
- ✅ `sqs-source-connector-basic.properties` - Minimal example

## 🔧 Technical Details

### Key Commits

**DLQ Implementation** (`af1d9dc`)
- Implemented production-grade DLQ with full error tracking
- Error headers: exception class, message, stacktrace, timestamp
- Backward compatible: deletes messages if no DLQ configured

**E2E Integration Tests** (`20edd85`, `2312086`)
- Actual connector execution (not mocked)
- Reliable polling logic with consecutive empty poll tracking
- Full message flow validation using Testcontainers

**Test Coverage** (`9b787a4`)
- Added 31 new tests across 3 test files
- Coverage improved from ~60% to ~85%+

**Multi-Cloud Auth** (`c33ad15`)
- ECS Fargate task role auto-detection
- AWS credentials provider chain
- Cross-account assume role with external ID

**Build Optimization** (`3abece3`)
- BuildKit cache mounts for Maven dependencies
- Alpine-based builder image
- 85% faster rebuilds

**Java 11 Compatibility** (`d3bda8f`)
- Replaced `Stream.toList()` with `collect(Collectors.toList())`

## 🧪 Test Coverage

- **Overall Coverage**: ~85%+
- **Unit Tests**: 40+ tests
- **Integration Tests**: 6 full e2e scenarios
- **CI Pipeline**: All tests passing on Java 11 & 17

**Test Execution Time**: ~3 minutes (unit + integration)

## 🚀 How to Test

### Local Testing with Docker
```bash
# Start LocalStack + Kafka stack
docker-compose up -d

# Build connector
mvn clean package

# Run tests
mvn test                          # Unit tests
RUN_INTEGRATION_TESTS=true mvn verify  # Integration tests
```

### Manual Testing
```bash
# 1. Create SQS queue in AWS console
# 2. Configure connector (see config/sqs-source-connector-basic.properties)
# 3. Deploy to Kafka Connect
# 4. Send messages to SQS
# 5. Verify messages appear in Kafka topic
```

## ✅ Checklist

- [x] Code follows project style guidelines (Checkstyle passing)
- [x] Unit tests added/updated
- [x] Integration tests added/updated
- [x] All tests passing in CI
- [x] Documentation updated
- [x] Example configurations provided
- [x] No breaking changes
- [x] Java 11 compatibility verified
- [x] Java 17 compatibility verified
- [x] Docker build tested
- [x] Multi-cloud authentication tested

## 🔄 Breaking Changes

None - this is the initial implementation.

## 📊 Performance

- **Throughput**: Configurable based on `sqs.max.messages` (1-10) and polling interval
- **Latency**: Near real-time with long polling enabled
- **Resource Usage**: Minimal - single task per queue maintains message ordering
- **Build Time**:
  - First build: 4-5 minutes
  - Rebuilds: 30-60 seconds (with BuildKit cache)

## 🎯 Next Steps

After merge:
- [ ] Tag v1.0.0 release
- [ ] Publish packaged artifact to releases
- [ ] Enable Codecov integration for coverage tracking
- [ ] Create monitoring/observability guide (optional)
- [ ] Create performance tuning guide (optional)

## 📚 Related Documentation

- [README.md](README.md) - Quick start and configuration reference
- [docs/ECS_DEPLOYMENT.md](docs/ECS_DEPLOYMENT.md) - ECS Fargate deployment guide
- [docs/DOCKER_BUILD_OPTIMIZATION.md](docs/DOCKER_BUILD_OPTIMIZATION.md) - Build optimization details
- [CONTRIBUTING.md](CONTRIBUTING.md) - Contribution guidelines

---

**Ready for production deployment!** 🚀
