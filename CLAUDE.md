You are tasked with implementing a custom Kafka Connect source connector—named kafka-connect-sqs-source-connector—which streams messages from AWS SQS queues into Apache Kafka topics.

Use the following existing repositories as reference for structure, standards and best practices:

stream‑reactor (by Lenses) 
GitHub

kafka‑connect‑storage‑cloud (by Confluent) 
GitHub

kafka‑connect‑quickstart (example project) 
GitHub

Your implementation tasks include:

Create repository layout and build configuration (Maven/Gradle) consistent with Kafka Connect plugin conventions.

Develop the connector code:

A SourceConnector class and SourceTask class that poll from AWS SQS, convert messages and push into Kafka topics.

Support for configurable parameters: SQS queue URL(s), AWS credentials/role, polling interval, batch size, visibility timeout, topic mapping, error/dead letter handling.

Use kafka-connect API (org.apache.kafka.connect) correctly, handle offsets/commit, tasks setup/teardown.

Provide unit tests and integration test suite, similar to quickstart example, to validate connector behaviour under normal, error and edge-case scenarios.

Include documentation: README with overview, configuration reference, usage example; CONTRIBUTING and LICENSE files; versioning policy (as seen in stream-reactor).

Provide sample Docker or docker-compose setup for local testing of connector with Kafka Connect cluster (similarly to quickstart).

Establish CI/CD workflow (GitHub Actions) for build, test, linting, packaging (analogous to stream-reactor and Confluent).

Create clear folder structure and module separation (for connector logic, common utilities, AWS integration, tests).

Ensure clear logging, metrics instrumentation, error handling, and good developer UX for deploying the connector.

Deliverables:

Repository initialized with all above components.

Buildable plugin JAR or ZIP ready for deployment in Kafka Connect.

Documentation and example configuration.

CI pipeline set up in GitHub Actions.

Use the referenced repos as style and structure guides for naming, layout, versioning and documentation.
Remember to set up SCRAM/SHA-512 Auth (mandatory) the rest of auths are preferable but optional for now


https://github.com/lensesio/stream-reactor
https://github.com/confluentinc/kafka-connect-storage-cloud
https://github.com/rueedlinger/kafka-connect-quickstart