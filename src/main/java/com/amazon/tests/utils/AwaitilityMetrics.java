package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AwaitilityMetrics {
    public static void measureAwaitility(
            String operation,
            Runnable awaitLogic) {

        long start =
                System.currentTimeMillis();

        awaitLogic.run();

        long duration =
                System.currentTimeMillis() - start;

        log.info(
                "AWAITILITY_METRIC operation={} duration={}ms",
                operation,
                duration);
    }
}
