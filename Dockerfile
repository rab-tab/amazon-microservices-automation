# ══════════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for API Automation Tests — sharding-aware
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

# Copy source code — this pulls in ShardingInterceptor.java (under
# src/test/java/com/amazon/automation/sharding/) and the ServiceLoader
# registration file (src/test/resources/META-INF/services/
# org.testng.ITestNGListener) automatically, since both live under src/.
COPY src ./src

# Compile tests (but don't run them yet)
RUN mvn test-compile -DskipTests

# ──────────────────────────────────────────────────────────────────────
# Stage 2: Runtime Stage
# ──────────────────────────────────────────────────────────────────────
# Still needs the full Maven+JDK toolchain (unlike the app services'
# runtime image, which only needs a JRE to run a pre-built jar) — this
# image runs `mvn test` directly, it doesn't just execute a jar.
FROM maven:3.9-eclipse-temurin-21-alpine

RUN apk add --no-cache curl \
    && rm -rf /var/cache/apk/*

WORKDIR /app

# Bring forward the resolved dependency cache from the builder stage —
# means `mvn test` at container-start time doesn't re-download anything,
# it's all already sitting in /root/.m2 from the image build itself.
COPY --from=builder /root/.m2 /root/.m2
COPY --from=builder /app/pom.xml ./pom.xml
COPY --from=builder /app/src ./src
COPY --from=builder /app/target ./target

RUN mkdir -p /app/target/allure-results /app/target/surefire-reports

# ── Service endpoints — in-cluster K8s DNS names, not localhost ──────
# Job pods run directly inside the "amazon" namespace, alongside the
# actual microservices — they reach them the same way any other pod
# does, via each Service's DNS name. No port-forwarding needed here,
# unlike the Jenkins-agent-driven "Port Forward Services" stage.
ENV BASE_URL=http://api-gateway:8080
ENV DB_HOST=postgres-service
ENV KAFKA_SERVERS=kafka-service:29092
ENV REDIS_HOST=redis-service

# TOTAL_SHARDS defaults to 1 here so this image also works standalone
# (`docker run`, or a non-sharded Job) — ShardingInterceptor only
# activates sharding when JOB_COMPLETION_INDEX is ALSO present, which
# only Kubernetes sets, and only on Indexed Jobs. Running this image
# any other way (plain `docker run`, a regular non-Indexed pod) safely
# runs the full suite with TOTAL_SHARDS=1 having no effect.
ENV TOTAL_SHARDS=1

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD mvn --version || exit 1

# Shell form (not exec form) so $TOTAL_SHARDS and $JOB_COMPLETION_INDEX
# actually get expanded by the shell at container start — exec form
# would pass them through literally, unexpanded.
CMD mvn test \
    -Dsurefire.suiteXmlFiles=src/test/resources/regression.xml \
    -Dsurefire.reportsDirectory=target/surefire-reports \
    -Dbase.url=${BASE_URL} \
    -Ddb.host=${DB_HOST} \
    -Ddb.port=5432 \
    -Ddb.username=amazon \
    -Ddb.password=amazon123 \
    -Dkafka.bootstrap.servers=${KAFKA_SERVERS} \
    -Dredis.host=${REDIS_HOST} \
    -Dredis.password=redis123 \
    --no-transfer-progress \
    -Dmaven.test.failure.ignore=false