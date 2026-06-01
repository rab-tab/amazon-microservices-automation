package com.amazon.tests.multithreadingFailureScenarios.revision;

import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.awaitility.Awaitility.await;

/**
 * Awaitility patterns from SDET architect perspective.
 *
 * Awaitility IS Awaitility — not Thread.sleep in disguise.
 * The difference:
 *   Thread.sleep(5000) — waits EXACTLY 5s whether ready in 100ms or 5s
 *   await().atMost(5s) — returns AS SOON AS condition met, up to 5s max
 */
public class AwaitilityPatterns {

    // ================================================================
    // SETUP: Configure Awaitility defaults for your framework
    // Call this in BaseTest @BeforeSuite
    // ================================================================

    public static void configureAwaitility(int threadCount) {

        // ✅ Give Awaitility its own thread pool sized to framework
        // Default uses ForkJoinPool — unpredictable under parallel tests
        ExecutorService awaitilityPool =
                Executors.newFixedThreadPool(
                        threadCount * 2,
                        r -> {
                            Thread t = new Thread(r);
                            t.setName("awaitility-"
                                    + t.getId());
                            t.setDaemon(true);
                            return t;
                        }
                );

        Awaitility.setDefaultPollInterval(
                Duration.ofMillis(500));       // check every 500ms
        Awaitility.setDefaultTimeout(
                Duration.ofSeconds(10));       // wait max 10s
        Awaitility.setDefaultPollDelay(
                Duration.ofMillis(100));       // initial delay before first check

        // ✅ Makes failures readable in reports
        Awaitility.setDefaultConditionEvaluationListener(
                condition -> {
                    if (!condition.isSatisfied()) {
                        System.out.printf(
                                "[Awaitility] Attempt %d | elapsed=%dms | %s%n",
                               // condition.getPollCount(),
                              //  condition.getElapsedTime().toMillis(),  // ✅ returns Duration
                                condition.getDescription());
                    }
                });

        System.out.println("Awaitility configured:"
                + " pollInterval=500ms"
                + " timeout=10s"
                + " pool=" + threadCount * 2 + " threads");
    }

    // ================================================================
    // PATTERN 2A: Basic await — replaces Thread.sleep
    // ================================================================

    static void basicAwait(Supplier<String> getOrderStatus,
                           String orderId) {

        // ❌ WRONG — arbitrary wait
        // Thread.sleep(5000);
        // assertThat(getOrderStatus.get()).isEqualTo("CONFIRMED");

        // ✅ CORRECT — wait until condition met
        await("Order " + orderId + " should be CONFIRMED")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .until(() -> getOrderStatus.get()
                        .equals("CONFIRMED"));

        System.out.println("✅ Order confirmed");
    }

    // ================================================================
    // PATTERN 2B: Async + Awaitility — parallel checks with smart wait
    // ================================================================

    /**
     * The most powerful pattern for microservice testing.
     *
     * Step 1: Fire async operations with CompletableFuture
     * Step 2: Use Awaitility to wait for each condition smartly
     *
     * Use case:
     *   POST /api/orders triggers:
     *     → Kafka event (verify within 5s)
     *     → DB record   (verify within 3s)
     *     → Email       (verify within 10s)
     *   Run all 3 verifications in parallel, each with own timeout
     */
    static void parallelAwaitPattern(
            String orderId,
            Supplier<Boolean> kafkaEventReceived,
            Supplier<String>  getOrderStatusFromDb,
            Supplier<Boolean> emailSent) throws Exception {

        System.out.println("\n--- Parallel Awaitility checks ---");

        ExecutorService pool = Executors.newFixedThreadPool(3);

        // ✅ Each validation runs in its own thread with its own timeout
        CompletableFuture<Void> kafkaCheck =
                CompletableFuture.runAsync(() ->
                                await("Kafka event for " + orderId)
                                        .atMost(Duration.ofSeconds(5))
                                        .pollInterval(Duration.ofMillis(200))
                                        .until(kafkaEventReceived::get),
                        pool
                );

        CompletableFuture<Void> dbCheck =
                CompletableFuture.runAsync(() ->
                                await("DB status for " + orderId)
                                        .atMost(Duration.ofSeconds(3))
                                        .pollInterval(Duration.ofMillis(300))
                                        .until(() -> getOrderStatusFromDb.get()
                                                .equals("CONFIRMED")),
                        pool
                );

        CompletableFuture<Void> emailCheck =
                CompletableFuture.runAsync(() ->
                                await("Email for " + orderId)
                                        .atMost(Duration.ofSeconds(10))
                                        .pollInterval(Duration.ofSeconds(1))
                                        .until(emailSent::get),
                        pool
                );

        // ✅ Wait for all — fail if any one fails
        try {
            CompletableFuture
                    .allOf(kafkaCheck, dbCheck, emailCheck)
                    .get();

            System.out.println("✅ All async validations passed");

        } catch (ExecutionException e) {
            // Unwrap to get the actual ConditionTimeoutException
            throw new AssertionError(
                    "Async validation failed: "
                            + e.getCause().getMessage(), e.getCause());
        } finally {
            pool.shutdown();
        }
    }

    // ================================================================
    // PATTERN 2C: Custom poll interval per condition
    // ================================================================

    /**
     * Different async operations have different natural poll rates.
     * Awaitility lets you tune each one independently.
     */
    static void tunedPollIntervals(
            String orderId,
            Supplier<String>  getOrderStatus,
            Supplier<Integer> getKafkaMessageCount,
            Supplier<Boolean> isPaymentProcessed) {

        System.out.println("\n--- Tuned poll intervals ---");

        // DB check — fast, check often
        await("Order status in DB")
                .atMost(Duration.ofSeconds(3))
                .pollInterval(Duration.ofMillis(200))  // fast poll
                .pollDelay(Duration.ofMillis(50))      // start checking almost immediately
                .until(() -> !getOrderStatus.get()
                        .equals("PENDING"));

        // Kafka check — slightly slower, needs broker round trip
        await("Kafka message count")
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(500))  // medium poll
                .pollDelay(Duration.ofMillis(200))     // give Kafka time to process
                .until(() -> getKafkaMessageCount.get() > 0);

        // Payment check — external service, check less frequently
        await("Payment processing")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofSeconds(1))   // slow poll
                .pollDelay(Duration.ofSeconds(1))      // payment takes at least 1s
                .until(isPaymentProcessed::get);

        System.out.println("✅ All conditions met with tuned intervals");
    }

    // ================================================================
    // PATTERN 2D: ignoreExceptions — handle transient failures
    // ================================================================

    /**
     * Between async operations, services may temporarily return errors.
     * Example: order not yet in DB → 404 → don't fail test, retry.
     */
    static void ignoreExceptionsPattern(
            String orderId,
            Supplier<String> getOrderStatus) {

        System.out.println("\n--- ignoreExceptions pattern ---");

        await("Order " + orderId + " to appear in DB")
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))

                // ✅ Don't fail on these — they're expected during async propagation
                .ignoreException(
                        java.net.ConnectException.class)  // service starting
                .ignoreExceptionsMatching(
                        e -> e.getMessage() != null
                                && e.getMessage().contains("404")) // not yet created

                .until(() -> {
                    String status = getOrderStatus.get();
                    return status != null
                            && !status.equals("PENDING");
                });

        System.out.println("✅ Order visible in DB");
    }

    // ================================================================
    // PATTERN 2E: conditionEvaluationListener — rich failure messages
    // ================================================================

    /**
     * When Awaitility times out, default message is vague.
     * Add a listener to log each poll attempt → better failure diagnosis.
     */
    static void richFailureMessages(
            String orderId,
            Supplier<String> getOrderStatus) {

        AtomicReference<String> lastStatus =
                new AtomicReference<>("UNKNOWN");

        try {
            await("Order " + orderId + " CONFIRMED")
                    .atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(500))
                    .conditionEvaluationListener(condition -> {
                        String current = getOrderStatus.get();
                        lastStatus.set(current);
                        System.out.printf(
                                "  [Poll %d] orderId=%s status=%s"
                                        + " elapsed=%dms remaining=%dms%n",
                            //    condition.getPollCount(),
                                orderId,
                                current,
                                condition.getElapsedTimeInMS(),
                                condition.getRemainingTimeInMS());
                    })
                    .until(() -> getOrderStatus.get()
                            .equals("CONFIRMED"));

        } catch (ConditionTimeoutException e) {
            // ✅ Rich failure message with last known state
            throw new AssertionError(
                    String.format(
                            "Order %s never reached CONFIRMED."
                                    + " Last status: %s."
                                    + " Check order-service logs.",
                            orderId,
                            lastStatus.get()),
                    e
            );
        }
    }
}