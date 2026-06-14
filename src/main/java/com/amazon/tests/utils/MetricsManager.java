package com.amazon.tests.utils;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;


public class MetricsManager {

    private static final MeterRegistry registry =
            new SimpleMeterRegistry();

    public static MeterRegistry registry() {
        return registry;
    }

    public static void recordApiLatency(
            String endpoint,
            long durationMs) {

        Timer.builder("api.latency")
                .tag("endpoint", endpoint)
                .register(registry)
                .record(Duration.ofSeconds(durationMs));
    }

    public static void recordKafkaWait(
            String topic,
            long durationMs) {

        Timer.builder("kafka.wait")
                .tag("topic", topic)
                .register(registry)
                .record(Duration.ofSeconds(durationMs));
    }

    public static void incrementKafkaConsumed(
            String topic) {

        Counter.builder("kafka.records.consumed")
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    public static void incrementKafkaMatched(
            String topic) {

        Counter.builder("kafka.records.matched")
                .tag("topic", topic)
                .register(registry)
                .increment();
    }

    public static void recordAwaitility(
            String operation,
            long durationMs) {

        Timer.builder("awaitility.wait")
                .tag("operation", operation)
                .register(registry)
                .record(Duration.ofSeconds(durationMs));
    }

    public static void recordUserSeeding(
            long durationMs) {

        Timer.builder("seeding.user")
                .register(registry)
                .record(Duration.ofSeconds(durationMs));
    }

    public static void recordProductSeeding(
            long durationMs) {

        Timer.builder("seeding.product")
                .register(registry)
                .record(Duration.ofSeconds(durationMs));
    }
}
