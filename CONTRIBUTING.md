# Contributing to Kafka Connect SQS Source Connector

Thank you for your interest in contributing! This document provides guidelines and instructions for contributing to this project.

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment for all contributors.

## How to Contribute

### Reporting Bugs

Before submitting a bug report:
1. Check existing issues to avoid duplicates
2. Verify you're using the latest version
3. Collect relevant information (logs, configuration, etc.)

When submitting a bug report, include:
- Clear description of the issue
- Steps to reproduce
- Expected vs actual behavior
- Environment details (Kafka version, Java version, AWS region)
- Relevant logs and error messages
- Connector configuration (sanitize sensitive data)

### Suggesting Features

We welcome feature suggestions! Please:
1. Check if the feature has already been requested
2. Provide a clear use case
3. Explain how it benefits users
4. Consider implementation complexity

### Pull Requests

#### Before You Start

1. **Read the quickstart guide**: See [QUICKSTART.md](QUICKSTART.md) to understand the codebase (30 minutes)
2. **Discuss major changes**: Open an issue first for significant features or architectural changes
3. **Check existing PRs**: Avoid duplicate work
4. **Fork the repository**: Create your fork for development

#### Development Workflow

1. **Clone your fork**:
   ```bash
   git clone https://github.com/YOUR_USERNAME/kafka-connect-sqs-source-connector.git
   cd kafka-connect-sqs-source-connector
   ```

2. **Create a feature branch**:
   ```bash
   git checkout -b feature/your-feature-name
   ```

3. **Make your changes**:
   - Follow the coding standards (see below)
   - Add tests for new functionality
   - Update documentation as needed

4. **Test your changes**:
   ```bash
   # Run unit tests
   mvn test

   # Run integration tests
   export RUN_INTEGRATION_TESTS=true
   mvn verify

   # Check code style
   mvn checkstyle:check

   # Test with Docker Compose
   docker-compose up -d
   ./docker/register-connector.sh
   ```

5. **Commit your changes**:
   ```bash
   git add .
   git commit -m "feat: add your feature description"
   ```

   Follow [Conventional Commits](https://www.conventionalcommits.org/):
   - `feat:` New feature
   - `fix:` Bug fix
   - `docs:` Documentation changes
   - `test:` Test additions or modifications
   - `refactor:` Code refactoring
   - `chore:` Maintenance tasks

6. **Push to your fork**:
   ```bash
   git push origin feature/your-feature-name
   ```

7. **Open a Pull Request**:
   - Provide a clear description
   - Reference related issues
   - Include test results
   - Update CHANGELOG.md (if applicable)

## Coding Standards

### Java Code Style

We follow standard Java conventions with these specifics:

- **Indentation**: 4 spaces (no tabs)
- **Line length**: Maximum 120 characters
- **Braces**: Always use braces, even for single-line blocks
- **Naming**:
  - Classes: `PascalCase`
  - Methods/variables: `camelCase`
  - Constants: `UPPER_SNAKE_CASE`
  - Packages: lowercase

### Code Quality

- **No compiler warnings**: Code must compile without warnings
- **Checkstyle**: Must pass `mvn checkstyle:check`
- **Test coverage**: Aim for >80% coverage for new code
- **Documentation**: Public APIs must have Javadoc

### Example

```java
package io.connect.sqs.example;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example class demonstrating code style.
 */
public class ExampleClass {

    private static final Logger log = LoggerFactory.getLogger(ExampleClass.class);
    private static final int DEFAULT_TIMEOUT = 30;

    private final String configValue;

    /**
     * Constructor.
     *
     * @param configValue Configuration value
     */
    public ExampleClass(String configValue) {
        this.configValue = configValue;
    }

    /**
     * Process a message.
     *
     * @param message Message to process
     * @return Processed result
     */
    public String processMessage(String message) {
        if (message == null || message.isEmpty()) {
            log.warn("Empty message received");
            return null;
        }

        log.debug("Processing message: {}", message);
        return message.toUpperCase();
    }
}
```

## Testing Guidelines

### Unit Tests

- Use JUnit 5
- Use AssertJ for assertions
- Mock external dependencies (AWS SDK, Kafka)
- Test edge cases and error conditions

```java
@Test
void shouldHandleNullMessage() {
    ExampleClass example = new ExampleClass("config");

    String result = example.processMessage(null);

    assertThat(result).isNull();
}
```

### Integration Tests

- Use Testcontainers for external services
- Test end-to-end scenarios
- Verify connector behavior under various conditions

```java
@Testcontainers
class ExampleIT {
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:latest"))
        .withServices(SQS);

    @Test
    void shouldProcessMessagesFromSqs() {
        // Test implementation
    }
}
```

## Documentation

### Code Documentation

- All public classes and methods must have Javadoc
- Include `@param` and `@return` tags
- Explain complex logic with inline comments

### User Documentation

Update relevant documentation:
- README.md: For user-facing changes
- Configuration examples: For new config options
- Architecture diagrams: For structural changes

## Build and Release Process

### Building Locally

```bash
# Clean build
mvn clean package

# Skip tests
mvn clean package -DskipTests

# Build Docker image
docker build -t kafka-connect-sqs:local .
```

### Release Checklist

For maintainers preparing a release:

1. Update version in `pom.xml`
2. Update CHANGELOG.md
3. Run full test suite
4. Build and test Docker image
5. Create git tag: `git tag v1.0.0`
6. Push tag: `git push origin v1.0.0`
7. Create GitHub release
8. Publish artifacts (if applicable)

## Getting Help

If you need help:
- Check existing documentation
- Search through issues
- Ask in discussions
- Reach out to maintainers

## Review Process

After submitting a PR:
1. Automated checks run (CI, tests, style)
2. Maintainers review code
3. Requested changes are addressed
4. PR is approved and merged

Typical review timeline: 3-7 days

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0.

## Recognition

Contributors are recognized in:
- README.md acknowledgments
- GitHub contributors page
- Release notes (for significant contributions)

Thank you for contributing! 🎉
