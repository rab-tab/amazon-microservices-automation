package com.amazon.tests.utils.validators;


import org.awaitility.Awaitility;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Reusable async validation API for your test classes.
 *
 * SDETs call:
 *   asyncValidator.waitForOrderStatus(orderId, "CONFIRMED")
 *   asyncValidator.waitForKafkaEvent(topic, key)
 *   asyncValidator.validateAll(checks)  ← parallel
 *
 * Never touches Thread.sleep.
 * Never hardcodes timeout values.
 * All timeouts configured from framework config.
 */
public class AsyncValidator {

    private final AsyncConfig config;
    private final ExecutorService validationPool;

    public AsyncValidator(AsyncConfig config, int threadCount) {
        this.config = config;
        this.validationPool = Executors.newFixedThreadPool(
                threadCount * 2,
                r -> {
                    Thread t = new Thread(r);
                    t.setName("async-validator-" + t.getId());
                    t.setDaemon(true);
                    return t;
                }
        );
    }

    // ── Config — all timeouts in one place ───────────────────────────

    public record AsyncConfig(
            Duration dbPropagationTimeout,    // how long DB takes to reflect
            Duration kafkaEventTimeout,        // how long Kafka takes to deliver
            Duration paymentProcessingTimeout, // how long payment takes
            Duration emailTimeout,             // how long email takes
            Duration pollInterval,             // how often to check
            Duration initialDelay             // wait before first check
    ) {
        // ✅ Factory methods per environment
        public static AsyncConfig forLocal() {
            return new AsyncConfig(
                    Duration.ofSeconds(3),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(15),
                    Duration.ofMillis(300),
                    Duration.ofMillis(100)
            );
        }

        public static AsyncConfig forCI() {
            return new AsyncConfig(
                    Duration.ofSeconds(5),   // CI is slower
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(20),
                    Duration.ofSeconds(30),
                    Duration.ofMillis(500),
                    Duration.ofMillis(200)
            );
        }

        public static AsyncConfig forStaging() {
            return new AsyncConfig(
                    Duration.ofSeconds(8),   // staging has real network latency
                    Duration.ofSeconds(15),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(1),
                    Duration.ofMillis(500)
            );
        }
    }

    // ── Single condition wait ─────────────────────────────────────────

    public void waitFor(String description,
                        BooleanSupplier condition,
                        Duration timeout) {
        try {
            Awaitility.await(description)
                    .atMost(timeout)
                    .pollInterval(config.pollInterval())
                    .pollDelay(config.initialDelay())
                    .ignoreExceptions()
                    .until(condition::getAsBoolean);

        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new AssertionError(
                    "Condition not met within "
                            + timeout.toSeconds() + "s: "
                            + description, e);
        }
    }

    // ── Domain-specific waiters ──────────────────────────────────────

    public void waitForOrderStatus(String orderId,
                                   String expectedStatus,
                                   Supplier<String> getStatus) {
        waitFor(
                "Order " + orderId + " to reach " + expectedStatus,
                () -> expectedStatus.equals(getStatus.get()),
                config.dbPropagationTimeout()
        );
    }

    public void waitForKafkaEvent(String description,
                                  BooleanSupplier kafkaCheck) {
        waitFor(
                description,
                kafkaCheck,
                config.kafkaEventTimeout()
        );
    }

    public void waitForPayment(String orderId,
                               BooleanSupplier paymentCheck) {
        waitFor(
                "Payment processed for " + orderId,
                paymentCheck,
                config.paymentProcessingTimeout()
        );
    }

    // ── Parallel validation — the most useful method ─────────────────

    /**
     * Run multiple async validations in parallel.
     * All must pass. Total time = longest individual check.
     *
     * Usage:
     *   asyncValidator.validateAll(
     *       check("DB status", () -> getStatus().equals("CONFIRMED"),
     *             config.dbPropagationTimeout()),
     *       check("Kafka event", () -> kafkaReceived,
     *             config.kafkaEventTimeout()),
     *       check("Payment done", () -> paymentProcessed,
     *             config.paymentProcessingTimeout())
     *   );
     */
    public void validateAll(AsyncCheck... checks) throws Exception {
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (AsyncCheck check : checks) {
            CompletableFuture<Void> future =
                    CompletableFuture.runAsync(() ->
                                    waitFor(check.description(),
                                            check.condition(),
                                            check.timeout()),
                            validationPool
                    );
            futures.add(future);
        }

        Duration maxTimeout = Arrays.stream(checks)
                .map(AsyncCheck::timeout)
                .max(Comparator.naturalOrder())
                .orElse(Duration.ofSeconds(30));

        try {
            CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .get();

        } catch (ExecutionException e) {
            throw new AssertionError(
                    "One or more async validations failed: "
                            + e.getCause().getMessage(),
                    e.getCause());
        }
    }

    // ── AsyncCheck builder ────────────────────────────────────────────

    public record AsyncCheck(
            String description,
            BooleanSupplier condition,
            Duration timeout
    ) {}

    public static AsyncCheck check(String description,
                                   BooleanSupplier condition,
                                   Duration timeout) {
        return new AsyncCheck(description, condition, timeout);
    }

    public void shutdown() {
        validationPool.shutdown();
    }
}