# Multi-stage Dockerfile for building and packaging the connector

# Stage 1: Build the connector
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /build

# Copy pom.xml and download dependencies (for caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src
COPY checkstyle.xml .

# Build the connector package
RUN mvn clean package -DskipTests

# Stage 2: Create the final connector image
FROM confluentinc/cp-kafka-connect:7.5.0

# Metadata
LABEL maintainer="your-email@example.com"
LABEL description="Kafka Connect SQS Source Connector"

# Create plugin directory
RUN mkdir -p /usr/share/java/kafka-connect-sqs

# Copy and extract connector package in one layer
COPY --from=builder /build/target/kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip \
    /usr/share/java/kafka-connect-sqs/

# Extract using jar command and clean up
RUN cd /usr/share/java/kafka-connect-sqs && \
    jar xf kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip && \
    rm -f kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip

# Set plugin path
ENV CONNECT_PLUGIN_PATH="/usr/share/java,/usr/share/confluent-hub-components"

# Expose Kafka Connect REST API port
EXPOSE 8083
