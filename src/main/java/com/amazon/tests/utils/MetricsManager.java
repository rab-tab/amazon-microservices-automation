package com.amazon.tests.utils;

//import edu.emory.mathcs.backport.java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

import java.time.Duration;



public class MetricsManager {

    private static final MetricsManager INSTANCE =
            new MetricsManager();

    private final Timer testDuration;

    private final Timer sagaLatency;

    private MetricsManager() {

        testDuration =
                Timer.builder("test_duration_seconds")
                        .register(registry);

        sagaLatency =
                Timer.builder("saga_latency_seconds")
                        .register(registry);
    }
    public static MetricsManager getInstance() {
        return INSTANCE;
    }
    private static final PrometheusMeterRegistry registry =
            new PrometheusMeterRegistry(
                    PrometheusConfig.DEFAULT);
    private static final Counter kafkaConsumed =
            registry.counter("kafka_records_consumed_total");

    private static final Counter kafkaMatched =
            registry.counter("kafka_records_matched_total");

    private static final Timer kafkaWait =
            registry.timer("kafka_wait");

    public static MeterRegistry registry() {
        return registry;
    }

    public static PrometheusMeterRegistry prometheusRegistry() {
        return registry;
    }
    public static void recordKafkaConsumed() {
        kafkaConsumed.increment();
    }

    public static void recordKafkaMatched() {
        kafkaMatched.increment();
    }

    public static void recordKafkaWait(long millis) {
        kafkaWait.record(
                Duration.ofSeconds(millis)
        );
    }

    public static void recordApiLatency(
            String endpoint,
            long durationMs) {

        Timer.builder("api.latency")
                .tag("endpoint", endpoint)
                .register(registry)
                .record(Duration.ofMillis(durationMs));
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
    // Test Results
    private static final Counter testPassed =
            Counter.builder("test_passed_total")
                    .register(registry);

    private static final Counter testFailed =
            Counter.builder("test_failed_total")
                    .register(registry);

    private static final Counter testRetry =
            Counter.builder("test_retry_total")
                    .register(registry);

    // Awaitility
    private static final Counter awaitilityTimeout =
            Counter.builder("awaitility_timeout_total").description(
                            "Number of Awaitility timeouts")
                    .register(registry);

    // Seeding failures
    private static final Counter userSeedFailures =
            Counter.builder("user_seed_failures_total")
                    .register(registry);

    private static final Counter productSeedFailures =
            Counter.builder("product_seed_failures_total")
                    .register(registry);


    public void recordAwaitilityTimeout() {

        awaitilityTimeout.increment();
    }
    public void recordTestPassed() {
        testPassed.increment();
    }

    public void recordTestFailed() {
        testFailed.increment();
    }

    public void recordRetry() {
        testRetry.increment();
    }

    public void recordUserSeedFailure() {
        userSeedFailures.increment();
    }

    public void recordProductSeedFailure() {
        productSeedFailures.increment();
    }

    public void recordTestDuration(long millis) {
        testDuration.record(
                Duration.ofMillis(millis));
    }

    public void recordSagaLatency(long millis) {
        sagaLatency.record(
                Duration.ofMillis(millis));
    }


}
