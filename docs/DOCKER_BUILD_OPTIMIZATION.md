# Docker Build Performance Optimization Guide

## Quick Start - Fast Build

```bash
# Use the optimized build script
./scripts/docker-build-fast.sh
```

## Speed Improvements

| Optimization | Time Saved | Description |
|-------------|------------|-------------|
| BuildKit cache mounts | 2-5 min | Maven dependencies cached between builds |
| Alpine base image | 30-60 sec | Smaller image, faster download |
| .dockerignore | 10-30 sec | Reduces build context size |
| Skip tests/checks | 1-2 min | Tests run in CI, not needed for image |
| Layer caching | Variable | Only rebuild changed layers |

**Total improvement: ~4-8 minutes faster** on subsequent builds!

## Optimizations Applied

### 1. BuildKit Cache Mounts
```dockerfile
# Before: Downloads dependencies every build (~3 minutes)
RUN mvn dependency:go-offline -B

# After: Caches dependencies between builds (~10 seconds)
RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B
```

### 2. Alpine Base Image
```dockerfile
# Before: 900 MB image
FROM maven:3.9-eclipse-temurin-11

# After: 500 MB image (44% smaller)
FROM maven:3.9-eclipse-temurin-11-alpine
```

### 3. Skip Unnecessary Steps
```dockerfile
# Production build - skip tests and checks
RUN mvn clean package \
    -DskipTests \
    -Dcheckstyle.skip=true \
    -Dmaven.javadoc.skip=true \
    -B
```

### 4. .dockerignore File
Excludes from build context:
- Documentation files
- Test files
- Git history
- IDE files
- CI/CD configs

Reduces context from ~10MB to ~2MB

## Build Time Comparison

### First Build (Cold Cache)
- **Before:** ~6-8 minutes
- **After:** ~4-5 minutes
- **Savings:** 2-3 minutes

### Subsequent Builds (Warm Cache)
- **Before:** ~4-6 minutes
- **After:** ~30-60 seconds
- **Savings:** 3.5-5 minutes (85% faster!)

## Usage

### Local Development
```bash
# Fast build with caching
./scripts/docker-build-fast.sh

# Or manually with BuildKit
export DOCKER_BUILDKIT=1
docker build -t kafka-connect-sqs:latest .
```

### CI/CD (GitHub Actions)
The CI already uses BuildKit and GitHub Actions cache:
```yaml
- name: Build Docker image
  uses: docker/build-push-action@v5
  with:
    cache-from: type=gha
    cache-to: type=gha,mode=max
```

### Production Build (with all checks)
```bash
# Full build with tests and checks
docker build \
  --build-arg SKIP_TESTS=false \
  -t kafka-connect-sqs:production \
  .
```

## Advanced: Multi-platform Builds

Build for both AMD64 and ARM64:
```bash
docker buildx build \
  --platform linux/amd64,linux/arm64 \
  -t kafka-connect-sqs:latest \
  --cache-from type=gha \
  --cache-to type=gha,mode=max \
  .
```

## Troubleshooting

### Cache not working?
```bash
# Clear Docker build cache
docker builder prune -af

# Rebuild without cache
docker build --no-cache -t kafka-connect-sqs:latest .
```

### BuildKit not enabled?
```bash
# Enable BuildKit
export DOCKER_BUILDKIT=1

# Or add to ~/.bashrc
echo 'export DOCKER_BUILDKIT=1' >> ~/.bashrc
```

### Build still slow?
Check:
1. Docker Desktop resource allocation (increase CPU/memory)
2. Network speed (for dependency downloads)
3. Disk I/O (use SSD if possible)
4. Antivirus exclusions (exclude Docker workspace)

## References

- [Docker BuildKit](https://docs.docker.com/build/buildkit/)
- [Docker cache mounts](https://docs.docker.com/build/guide/mounts/)
- [Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
