package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SeedingMetrics {

    private long setupStart;

    private long userSeeding;

    private long productSeeding;

    public void start() {
        setupStart = System.currentTimeMillis();
    }

    public void recordUserSeeding(long duration) {
        userSeeding = duration;
    }

    public void recordProductSeeding(long duration) {
        productSeeding = duration;
    }

    public void print() {

        log.info("""
            ===== SETUP METRICS =====

            User Seeding={}ms

            Product Seeding={}ms

            Total Setup={}ms
            """,
                userSeeding,
                productSeeding,
                System.currentTimeMillis()
                        - setupStart);
    }
}
