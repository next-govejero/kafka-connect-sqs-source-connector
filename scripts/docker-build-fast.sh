#!/bin/bash
# Fast Docker build script using BuildKit optimizations

set -e

echo "🚀 Building Kafka Connect SQS Connector (Fast Mode)"
echo ""

# Enable BuildKit for faster builds
export DOCKER_BUILDKIT=1

# Build with cache
echo "Building with BuildKit cache mounts..."
docker build \
  --progress=plain \
  --build-arg BUILDKIT_INLINE_CACHE=1 \
  -t kafka-connect-sqs:latest \
  -t kafka-connect-sqs:dev \
  .

echo ""
echo "✅ Build complete!"
echo ""
echo "Image tags:"
echo "  - kafka-connect-sqs:latest"
echo "  - kafka-connect-sqs:dev"
echo ""
echo "To run: docker run -p 8083:8083 kafka-connect-sqs:latest"
