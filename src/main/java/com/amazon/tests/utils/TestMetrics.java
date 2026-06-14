package com.amazon.tests.utils;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class TestMetrics {

    private long testStart;

    private long apiCalls;
    private long apiLatency;

    private long kafkaRecordsConsumed;
    private long kafkaRecordsMatched;
    private long kafkaWaitTime;

    private long userSeedingTime;
    private long productSeedingTime;

    private long awaitilityTime;

    public void start() {
        testStart = System.currentTimeMillis();
    }

    public void recordApiCall(long duration) {
        apiCalls++;
        apiLatency += duration;
    }

    public void recordKafkaConsumed() {
        kafkaRecordsConsumed++;
    }

    public void recordKafkaMatched() {
        kafkaRecordsMatched++;
    }

    public void addKafkaWait(long duration) {
        kafkaWaitTime += duration;
    }

    public void recordUserSeeding(long duration) {
        userSeedingTime = duration;
    }

    public void recordProductSeeding(long duration) {
        productSeedingTime = duration;
    }

    public void recordAwaitility(long duration) {
        awaitilityTime += duration;
    }

    public void print() {

        log.info("""

================ TEST OBSERVABILITY ================

API Calls               : {}
Average API Latency     : {} ms

Kafka Records Consumed  : {}
Kafka Records Matched   : {}
Kafka Wait              : {} ms

User Seeding            : {} ms
Product Seeding         : {} ms

Awaitility Wait         : {} ms

====================================================

""",
                apiCalls,
                apiCalls == 0 ? 0 : apiLatency / apiCalls,

                kafkaRecordsConsumed,
                kafkaRecordsMatched,
                kafkaWaitTime,

                userSeedingTime,
                productSeedingTime,

                awaitilityTime
        );
    }
}