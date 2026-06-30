package com.amazon.tests.utils.metrics;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KafkaMetrics {

    private long startTime;

    private int recordsConsumed;

    private int recordsMatched;

    public void start() {
        startTime = System.currentTimeMillis();
    }

    public void incrementConsumed() {
        recordsConsumed++;
    }

    public void incrementMatched() {
        recordsMatched++;
    }

    public void print() {

        long duration =
                System.currentTimeMillis() - startTime;

        log.info("""
            ===== KAFKA METRICS =====

            Kafka Wait={} ms

            Records Consumed={}

            Records Matched={}

            Match Ratio={}%

            """,
                duration,
                recordsConsumed,
                recordsMatched,
                recordsConsumed == 0
                        ? 0
                        : (recordsMatched * 100.0)
                        / recordsConsumed
        );
    }
}