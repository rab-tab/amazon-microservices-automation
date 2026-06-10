package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
public class TestTimeline {

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

        return events.get(endEvent)
                - events.get(startEvent);
    }

    public void printSummary() {

        log.info("""
            ===== SAGA TIMELINE =====
            {}
            """, events);

        log.info(
                "Order->Kafka={} ms",
                durationBetween(
                        "ORDER_CREATED",
                        "ORDER_CREATED_EVENT"));

        log.info(
                "Kafka->Payment={} ms",
                durationBetween(
                        "ORDER_CREATED_EVENT",
                        "PAYMENT_COMPLETED"));

        log.info(
                "Payment->Confirm={} ms",
                durationBetween(
                        "PAYMENT_COMPLETED",
                        "ORDER_CONFIRMED"));

        log.info(
                "SagaLatency={} ms",
                durationBetween(
                        "ORDER_CREATED",
                        "ORDER_CONFIRMED"));
    }
}