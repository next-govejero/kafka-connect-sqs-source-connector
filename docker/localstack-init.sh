#!/bin/bash

# Initialize LocalStack SQS resources

echo "Initializing LocalStack SQS..."

# Create test queue
awslocal sqs create-queue --queue-name test-sqs-queue

# Get queue URL
QUEUE_URL=$(awslocal sqs get-queue-url --queue-name test-sqs-queue --query 'QueueUrl' --output text)

echo "Created SQS queue: $QUEUE_URL"

# Send some test messages
for i in {1..5}; do
  awslocal sqs send-message \
    --queue-url "$QUEUE_URL" \
    --message-body "Test message $i" \
    --message-attributes "MessageNumber={DataType=Number,StringValue=$i}"
done

echo "Sent 5 test messages to queue"
echo "LocalStack initialization complete"
