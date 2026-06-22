# ══════════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for API Automation Tests
# ══════════════════════════════════════════════════════════════════════

# ──────────────────────────────────────────────────────────────────────
# Stage 1: Build Stage (Maven Dependencies + Compilation)
# ──────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

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

# ── CHANGED: added python3 + py3-pip for XML result merging ──────────
# curl      → health checks (unchanged)
# python3   → runs merge_results.py in Aggregate Results stage
# py3-pip   → installs lxml for TestNG XML parsing
RUN apk add --no-cache curl python3 py3-pip \
    && pip3 install lxml --break-system-packages \
    && rm -rf /root/.cache/pip

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

# ── CHANGED: added parallel runner env vars ──────────────────────────
# GATEWAY_URL, TEST_ENV, ALLURE_RESULTS_DIR → unchanged
# CHUNK_INDEX   → which chunk this runner owns (0-based)
# TOTAL_CHUNKS  → total parallel runners in this build
#                 defaults to 1 so single-runner mode works without changes
ENV GATEWAY_URL=http://api-gateway:8080
ENV TEST_ENV=docker
ENV ALLURE_RESULTS_DIR=/app/test-results/allure-results
ENV CHUNK_INDEX=0
ENV TOTAL_CHUNKS=1
ENV SUITE_FILE=testng-api-gateway.xml,testng-kafka.xml,testng-order.xml

# Health check (optional - validates Maven is working)
HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD mvn --version || exit 1

# ── CHANGED: CMD uses CHUNK_INDEX to pick the right suite file ────────
# When TOTAL_CHUNKS=1 (default), testng-runner-0.xml = full suite → same as before
# When TOTAL_CHUNKS>1, Jenkins writes testng-runner-N.xml per runner
#   and mounts it at /app/testng-runner-${CHUNK_INDEX}.xml
# Shell form (not exec form) is required here so ${CHUNK_INDEX} is expanded
CMD mvn test \
    #-Dsurefire.suiteXmlFiles=testng-runner-${CHUNK_INDEX}.xml \
    -Dsurefire.suiteXmlFiles=${SUITE_FILE}
    -Dsurefire.reportsDirectory=target/surefire-reports \
    --no-transfer-progress \
    -Dmaven.test.failure.ignore=true

# ──────────────────────────────────────────────────────────────────────
# Usage Examples:
# ──────────────────────────────────────────────────────────────────────
# Build:
#   docker build -t automation-tests:latest .
#
# Run all tests (unchanged — TOTAL_CHUNKS=1 default, needs testng-runner-0.xml):
#   docker run --rm \
#     --network host \
#     -e GATEWAY_URL=http://localhost:8090 \
#     -v $(pwd)/testng-runner-0.xml:/app/testng-runner-0.xml:ro \
#     -v $(pwd)/target/surefire-reports:/app/target/surefire-reports \
#     automation-tests:latest
#
# Run as parallel runner N of M (Jenkins does this automatically):
#   docker run --rm \
#     --network host \
#     -e CHUNK_INDEX=1 \
#     -e TOTAL_CHUNKS=3 \
#     -e GATEWAY_URL=http://localhost:8090 \
#     -v $(pwd)/testng-runner-1.xml:/app/testng-runner-1.xml:ro \
#     -v $(pwd)/target/surefire-runner-1:/app/target/surefire-reports \
#     automation-tests:latest
#
# Run specific test (override CMD — unchanged):
#   docker run --rm \
#     --network host \
#     -e GATEWAY_URL=http://localhost:8090 \
#     automation-tests:latest \
#     mvn test -Dtest=EndToEndIdempotencyTest
#
# Run with volume mount for reports (unchanged pattern):
#   docker run --rm \
#     --network host \
#     -v $(pwd)/test-results:/app/test-results \
#     automation-tests:latest
# ──────────────────────────────────────────────────────────────────────