# ══════════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for API Automation Tests
# ══════════════════════════════════════════════════════════════════════

# ──────────────────────────────────────────────────────────────────────
# Stage 1: Build Stage (Maven Dependencies + Compilation)
# ──────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

# Set working directory
WORKDIR /app

# Copy only pom.xml first (for dependency caching)
COPY pom.xml .

# Download dependencies (cached layer if pom.xml unchanged)
RUN mvn dependency:go-offline -B

# Copy source code
COPY src ./src

# Compile tests (but don't run them yet)
RUN mvn test-compile -DskipTests

# ──────────────────────────────────────────────────────────────────────
# Stage 2: Runtime Stage (Lean image for execution)
# ──────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine

# Install curl for health checks
RUN apk add --no-cache curl

# Set working directory
WORKDIR /app

# Copy Maven dependencies from builder stage
COPY --from=builder /root/.m2 /root/.m2

# Copy compiled code and source
COPY --from=builder /app/pom.xml ./pom.xml
COPY --from=builder /app/src ./src
COPY --from=builder /app/target ./target

# Create directories for test results
RUN mkdir -p /app/test-results/allure-results \
             /app/test-results/surefire-reports \
             /app/logs

# Environment variables for test configuration
ENV GATEWAY_URL=http://api-gateway:8080
ENV TEST_ENV=docker
ENV ALLURE_RESULTS_DIR=/app/test-results/allure-results

# Health check (optional - validates Maven is working)
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD mvn --version || exit 1

# Default command: Run all tests
CMD ["mvn", "clean", "test"]

# ──────────────────────────────────────────────────────────────────────
# Usage Examples:
# ──────────────────────────────────────────────────────────────────────
# Build:
#   docker build -t automation-tests:latest .
#
# Run all tests:
#   docker run --rm \
#     --network microservices_network \
#     -e GATEWAY_URL=http://api-gateway:8080 \
#     automation-tests:latest
#
# Run specific test:
#   docker run --rm \
#     --network microservices_network \
#     -e GATEWAY_URL=http://api-gateway:8080 \
#     automation-tests:latest \
#     mvn test -Dtest=EndToEndIdempotencyTest
#
# Run with volume mount for reports:
#   docker run --rm \
#     --network microservices_network \
#     -v $(pwd)/test-results:/app/test-results \
#     automation-tests:latest
# ──────────────────────────────────────────────────────────────────────