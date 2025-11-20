# Security Policy

## Supported Versions

We release patches for security vulnerabilities. Currently supported versions:

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |
| < 1.0   | :x:                |

## Reporting a Vulnerability

We take the security of Kafka Connect SQS Source Connector seriously. If you have discovered a security vulnerability, please report it to us privately.

**Please do not report security vulnerabilities through public GitHub issues.**

### How to Report

1. **Email**: Send details to [your-security-email@example.com]
2. **GitHub Security Advisories**: Use GitHub's [private vulnerability reporting](https://github.com/next-govejero/kafka-connect-sqs-source-connector/security/advisories/new)

### What to Include

Please include the following information in your report:

- Type of vulnerability (e.g., SQL injection, credential exposure, etc.)
- Full paths of source file(s) related to the vulnerability
- Location of the affected source code (tag/branch/commit or direct URL)
- Step-by-step instructions to reproduce the issue
- Proof-of-concept or exploit code (if possible)
- Impact of the vulnerability, including how an attacker might exploit it

### Response Timeline

- **Initial Response**: Within 48 hours
- **Status Update**: Within 5 business days
- **Fix Timeline**: Depends on complexity, typically within 30 days for critical issues

## Security Best Practices

### AWS Credentials

1. **Never commit credentials** to the repository
2. **Use IAM roles** when possible (ECS Task Roles, EC2 Instance Roles)
3. **Use STS AssumeRole** for cross-account access
4. **Rotate credentials** regularly (access keys should be rotated every 90 days)
5. **Use AWS Secrets Manager or Parameter Store** for credential management

### Kafka Authentication

1. **SCRAM-SHA-512 is mandatory** in this connector for enhanced security
2. **Use TLS/SSL** for Kafka connections in production (`security.protocol=SASL_SSL`)
3. **Store JAAS credentials** securely (environment variables, secret managers)
4. **Restrict topic permissions** using Kafka ACLs

### Network Security

1. **Use VPC endpoints** for SQS access to avoid public internet
2. **Configure security groups** to restrict access
3. **Enable encryption in transit** for all AWS services
4. **Use private subnets** for connector deployments

### Configuration Security

1. **Validate all user inputs** (connector does extensive validation)
2. **Use principle of least privilege** for IAM policies
3. **Enable CloudTrail logging** for AWS API calls
4. **Monitor connector logs** for suspicious activity

### Dependency Security

- We use **Dependabot** for automatic dependency updates
- Security scans run in CI/CD pipeline
- Run `mvn dependency:analyze` to check for vulnerabilities
- Use `scripts/security-scan.sh` for local security scanning

## Known Security Considerations

### Message Content

- SQS messages are processed as-is without sanitization
- Implement application-level validation for message content
- Be cautious with claim check pattern - validate S3 URIs before retrieval
- Consider message size limits to prevent memory exhaustion

### Dead Letter Queue

- DLQ messages contain full exception details including stack traces
- Ensure DLQ topic has appropriate access controls
- Monitor DLQ for sensitive information leakage

### Schema Registry

- Schema Registry endpoints should be protected with authentication
- Validate schemas before registration to prevent malicious schemas
- Use SSL/TLS for Schema Registry connections

## Security Scanning

Run security scans locally:

```bash
# Maven dependency check
mvn dependency-check:check

# OWASP security scan (if configured)
./scripts/security-scan.sh

# Docker image scanning
docker scan kafka-connect-sqs-source:latest
```

## Disclosure Policy

- Vulnerabilities are disclosed after a fix is available
- We will credit researchers who report vulnerabilities (if desired)
- CVE numbers will be requested for significant vulnerabilities

## Security Updates

Security updates are published:
- In GitHub Security Advisories
- In release notes (CHANGELOG.md)
- Via GitHub notifications to watchers

## Contact

For security concerns: [your-security-email@example.com]

For general questions: Use GitHub Issues (but NOT for vulnerabilities)
