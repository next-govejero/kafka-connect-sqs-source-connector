#!/bin/bash

# Script to register the SQS Source Connector with Kafka Connect

CONNECT_HOST=${CONNECT_HOST:-localhost:8083}
CONNECTOR_NAME=${CONNECTOR_NAME:-sqs-source-connector}

# Wait for Kafka Connect to be ready
echo "Waiting for Kafka Connect to be ready..."
while ! curl -s http://${CONNECT_HOST}/ > /dev/null; do
  sleep 2
done
echo "Kafka Connect is ready"

# Create connector configuration
CONNECTOR_CONFIG='{
  "name": "'${CONNECTOR_NAME}'",
  "config": {
    "connector.class": "io.connect.sqs.SqsSourceConnector",
    "tasks.max": "1",

    "aws.region": "us-east-1",
    "aws.access.key.id": "test",
    "aws.secret.access.key": "test",

    "sqs.queue.url": "http://localstack:4566/000000000000/test-sqs-queue",
    "sqs.max.messages": "10",
    "sqs.wait.time.seconds": "5",
    "sqs.visibility.timeout.seconds": "30",
    "sqs.message.attributes.enabled": "true",
    "sqs.delete.messages": "true",

    "kafka.topic": "sqs-messages",

    "sasl.mechanism": "SCRAM-SHA-512",
    "security.protocol": "SASL_PLAINTEXT",
    "sasl.jaas.config": "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"connect-user\" password=\"connect-secret\";",

    "dlq.topic": "sqs-dlq",
    "max.retries": "3",
    "retry.backoff.ms": "1000",

    "poll.interval.ms": "1000",
    "message.converter.class": "io.connect.sqs.converter.DefaultMessageConverter"
  }
}'

# Register the connector
echo "Registering connector..."
curl -X POST \
  -H "Content-Type: application/json" \
  --data "${CONNECTOR_CONFIG}" \
  http://${CONNECT_HOST}/connectors

echo ""
echo "Connector registered successfully"

# Check connector status
echo "Checking connector status..."
curl -s http://${CONNECT_HOST}/connectors/${CONNECTOR_NAME}/status | jq '.'
