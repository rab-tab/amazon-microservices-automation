package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class TestTimeline {

    public static final String ORDER_CREATED =
            "ORDER_CREATED";

    public static final String ORDER_CREATED_EVENT =
            "ORDER_CREATED_EVENT";

    public static final String PAYMENT_COMPLETED =
            "PAYMENT_COMPLETED";

    public static final String ORDER_CONFIRMED =
            "ORDER_CONFIRMED";

    private final Map<String, Long> events =
            new LinkedHashMap<>();

    private final long startTime =
            System.currentTimeMillis();

    public void mark(String eventName) {
        events.put(
                eventName,
                System.currentTimeMillis() - startTime
        );
    }

    public long durationBetween(
            String startEvent,
            String endEvent) {

        Long start = events.get(startEvent);
        Long end = events.get(endEvent);

        if (start == null || end == null) {

            log.warn(
                    "Cannot calculate duration. Missing events. start={} exists={} end={} exists={}",
                    startEvent,
                    start != null,
                    endEvent,
                    end != null
            );

            return -1;
        }

        return end - start;
    }



    public void printSummary() {

        log.info("===== SAGA TIMELINE =====");
        log.info("{}", events);

        printMetric(
                "Order->Kafka",
                "ORDER_CREATED",
                "ORDER_CREATED_EVENT");

        printMetric(
                "Kafka->Payment",
                "ORDER_CREATED_EVENT",
                "PAYMENT_COMPLETED");

        printMetric(
                "Payment->Confirm",
                "PAYMENT_COMPLETED",
                "ORDER_CONFIRMED");

        printMetric(
                "SagaLatency",
                "ORDER_CREATED",
                "ORDER_CONFIRMED");
    }

    private void printMetric(
            String label,
            String start,
            String end) {

        long duration =
                durationBetween(start, end);

        if (duration >= 0) {
            log.info("{}={} ms", label, duration);
        }
    }
}