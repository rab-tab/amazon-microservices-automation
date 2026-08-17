#!/bin/sh
set -e

echo "Shard ${JOB_COMPLETION_INDEX} of ${TOTAL_SHARDS} starting..."

# Run tests but capture the exit code instead of letting `set -e` kill the
# script immediately — we need to upload results to S3 EITHER WAY, then
# exit with Maven's real code afterward so the Job still correctly
# reports failure when tests fail.
set +e
mvn test \
    -Dsurefire.suiteXmlFiles=src/test/resources/regression.xml \
    -Dsurefire.reportsDirectory=target/surefire-reports \
    -Denv=docker \
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
TEST_EXIT_CODE=$?
set -e

echo "Tests finished with exit code ${TEST_EXIT_CODE}. Uploading results to S3..."

# Each shard uploads to its own prefix under this build's results —
# BUILD_ID makes runs distinguishable, JOB_COMPLETION_INDEX makes
# shards within a run distinguishable. Aggregation later just syncs
# down everything under builds/${BUILD_ID}/.
S3_PREFIX="s3://${S3_BUCKET}/test-results/${BUILD_ID}/shard-${JOB_COMPLETION_INDEX}"

aws s3 cp target/surefire-reports "${S3_PREFIX}/surefire-reports" --recursive --only-show-errors || true
aws s3 cp target/allure-results "${S3_PREFIX}/allure-results" --recursive --only-show-errors || true
aws s3 cp target/extent-reports "${S3_PREFIX}/extent-reports" --recursive --only-show-errors || true

echo "✅ Shard ${JOB_COMPLETION_INDEX} results uploaded to ${S3_PREFIX}"

# Exit with the REAL test result, not the upload's — a failed upload
# shouldn't mask a real test failure, and vice versa.
exit ${TEST_EXIT_CODE}