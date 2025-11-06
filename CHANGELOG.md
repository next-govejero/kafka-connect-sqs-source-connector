# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2025-11-06

### Added
- **Core Connector Implementation**
  - Initial implementation of Kafka Connect SQS Source Connector
  - Support for AWS SQS message polling with configurable batch sizes (1-10 messages)
  - Message attribute preservation as Kafka record headers
  - Configurable visibility timeouts and polling intervals
  - Automatic message deletion after successful processing

- **Authentication & Security**
  - SCRAM-SHA-512 authentication (mandatory) for Kafka connections
  - Flexible AWS authentication with multiple methods:
    - Static credentials (access key + secret)
    - AWS credentials profiles
    - ECS Fargate task roles (auto-detection)
    - EC2 instance profiles
    - STS assume role with external ID support
  - Multi-cloud deployment support (AWS, Azure, GCP, bare metal)
  - Secure credential handling with AWS SDK v2

- **Dead Letter Queue (DLQ) Support**
  - Production-grade DLQ implementation for failed messages
  - Comprehensive error tracking in Kafka headers:
    - Exception class name
    - Exception message
    - Full stack trace (truncated to 8KB if needed)
    - Error timestamp
    - Original SQS message ID and queue URL
  - Original message body preserved in DLQ
  - Configurable behavior: route to DLQ or delete failed messages

- **Testing & Quality**
  - Comprehensive test coverage (85%+)
  - 18 unit tests for SqsSourceTask
  - 16 unit tests for SqsClient (authentication and configuration)
  - 8 unit tests for DefaultMessageConverter
  - 5 unit tests for SqsSourceConnector
  - 6 end-to-end integration tests validating full SQS → Connector → Kafka flow
  - Integration tests using Testcontainers (LocalStack + Kafka)
  - Code quality checks (Checkstyle, JaCoCo coverage reporting)

- **Build & CI/CD**
  - Optimized Docker build with BuildKit cache mounts (85% faster rebuilds)
  - Docker Compose setup for local development
  - GitHub Actions CI/CD pipeline with:
    - Matrix builds (Java 11 & 17)
    - Unit and integration test execution
    - Docker build validation
    - Semantic PR validation
    - Automated code review checks
  - GitHub Dependabot for automatic dependency updates
  - Codecov integration for test coverage tracking

- **Documentation**
  - Comprehensive README with quick start and configuration reference
  - ECS Fargate deployment guide with IAM policies and task definitions
  - Docker build optimization guide
  - Contributing guidelines
  - 6 example configurations:
    - ECS Fargate with task role
    - Azure Key Vault integration
    - GCP Secret Manager integration
    - Bare metal with static credentials
    - Standalone Kafka Connect
    - Basic minimal configuration

- **Configuration & Monitoring**
  - 18+ configurable parameters with validation
  - Built-in metrics tracking (messages received, sent, deleted, failed)
  - Comprehensive logging with SLF4J
  - Support for custom message converters

### Fixed
- Java 11 compatibility (replaced Stream.toList() with collect(Collectors.toList()))
- Docker permission issues when extracting connector package
- E2E test polling logic for reliable large batch processing (50+ messages)
- Test assertions for proper error handling validation
- GitHub Actions workflow permissions for PR checks

### Performance
- Optimized Docker build time:
  - First build: 4-5 minutes
  - Rebuilds: 30-60 seconds (85% improvement)
- Efficient message batching with configurable batch sizes
- Long polling support to reduce empty polls
- Single task per queue maintains message ordering

### Security
- Mandatory SCRAM-SHA-512 authentication for Kafka connections
- Secure credential handling with AWS SDK v2
- Support for cross-account SQS access with assume role
- No hardcoded credentials in configuration examples
