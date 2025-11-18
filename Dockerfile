# syntax=docker/dockerfile:1.4

# Multi-stage Dockerfile for building and packaging the connector
# Optimized for fast builds with BuildKit cache mounts

# Stage 1: Build the connector
FROM maven:3.9-eclipse-temurin-11-alpine AS builder

WORKDIR /build

# Copy pom.xml first for better layer caching
COPY pom.xml .

# Download dependencies with cache mount (significantly faster on rebuilds)
# The cache persists between builds
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

# Copy source code
COPY src ./src
COPY checkstyle.xml .

# Build with cache mount and skip unnecessary checks for faster builds
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -Dcheckstyle.skip=true -Dmaven.javadoc.skip=true -B

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

# Extract using jar command, move contents up one level, and clean up
RUN cd /usr/share/java/kafka-connect-sqs && \
    jar xf kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip && \
    mv kafka-connect-sqs-source-1.0.0-SNAPSHOT/* . && \
    rmdir kafka-connect-sqs-source-1.0.0-SNAPSHOT && \
    rm -f kafka-connect-sqs-source-1.0.0-SNAPSHOT-package.zip

# Set plugin path
ENV CONNECT_PLUGIN_PATH="/usr/share/java,/usr/share/confluent-hub-components"

# Expose Kafka Connect REST API port
EXPOSE 8083
