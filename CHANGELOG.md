# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial implementation of Kafka Connect SQS Source Connector
- Support for AWS SQS message polling with configurable batch sizes
- SCRAM-SHA-512 authentication (mandatory)
- Flexible AWS authentication (access keys, IAM roles, STS assume role)
- Message attribute preservation as Kafka record headers
- Dead letter queue support for failed messages
- Configurable visibility timeouts and polling intervals
- Comprehensive unit and integration tests
- Docker Compose setup for local development
- Complete documentation (README, CONTRIBUTING)
- CI/CD pipeline with GitHub Actions
- Code quality checks (Checkstyle, JaCoCo)
- Example configuration files

### Security
- Mandatory SCRAM-SHA-512 authentication for Kafka connections
- Secure credential handling with AWS SDK

## [1.0.0-SNAPSHOT] - 2024-01-XX

### Initial Release
- First version of the Kafka Connect SQS Source Connector
