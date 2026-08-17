# ══════════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for API Automation Tests — sharding + S3 upload
# ══════════════════════════════════════════════════════════════════════

FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn test-compile -DskipTests

FROM maven:3.9-eclipse-temurin-21-alpine

# curl → health checks; aws-cli → uploading results to S3 after each shard
RUN apk add --no-cache curl aws-cli \
    && rm -rf /var/cache/apk/*

WORKDIR /app

COPY --from=builder /root/.m2 /root/.m2
COPY --from=builder /app/pom.xml ./pom.xml
COPY --from=builder /app/src ./src
COPY --from=builder /app/target ./target

RUN mkdir -p /app/target/allure-results /app/target/surefire-reports /app/target/extent-reports

# In-cluster K8s DNS names — pods run inside the "amazon" namespace
# directly, no port-forwarding needed.
ENV BASE_URL=http://api-gateway:8080
ENV DB_HOST=postgres-service
ENV KAFKA_SERVERS=kafka-service:29092
ENV REDIS_HOST=redis-service

# Defaults so this image also works standalone (docker run, non-Indexed
# pod) — sharding only activates when JOB_COMPLETION_INDEX is ALSO set,
# which only Kubernetes does, on Indexed Jobs.
ENV TOTAL_SHARDS=1
ENV JOB_COMPLETION_INDEX=0
ENV BUILD_ID=local
ENV S3_BUCKET=amazon-microservices-build-artifacts-978185568053

COPY run-shard.sh /app/run-shard.sh
RUN chmod +x /app/run-shard.sh

HEALTHCHECK --interval=30s --timeout=10s --start-period=5s --retries=3 \
  CMD mvn --version || exit 1

CMD ["/app/run-shard.sh"]