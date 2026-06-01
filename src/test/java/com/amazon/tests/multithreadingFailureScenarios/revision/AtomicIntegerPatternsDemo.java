package com.amazon.tests.multithreadingFailureScenarios.revision;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.*;

public class AtomicIntegerPatternsDemo {

    // ================================================================
    // PATTERN 1: Basic counter — broken vs atomic
    // ================================================================
    static int brokenCounter = 0;
    static AtomicInteger atomicCounter = new AtomicInteger(0);

    static void pattern1_BasicCounter() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PATTERN 1: Basic Counter — broken vs atomic");
        System.out.println("=".repeat(60));

        brokenCounter = 0;
        atomicCounter.set(0);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(1000);

        for (int i = 0; i < 1000; i++) {
            pool.submit(() -> {
                brokenCounter++;        // ❌ not atomic
                atomicCounter.incrementAndGet(); // ✅ atomic
                latch.countDown();
            });
        }

        latch.await();
        pool.shutdown();

        System.out.println("Expected : 1000");
        System.out.println("Broken   : " + brokenCounter +
                (brokenCounter == 1000 ? " ✅ (got lucky)" : " ❌ LOST INCREMENTS"));
        System.out.println("Atomic   : " + atomicCounter.get() + " ✅ always correct");
    }

    // ================================================================
    // PATTERN 2: Test result counters — most common in frameworks
    // ================================================================
    static class TestResultTracker {
        private final AtomicInteger total   = new AtomicInteger(0);
        private final AtomicInteger passed  = new AtomicInteger(0);
        private final AtomicInteger failed  = new AtomicInteger(0);
        private final AtomicInteger skipped = new AtomicInteger(0);

        // Called by each thread when test finishes
        public void recordPass()    { total.incrementAndGet(); passed.incrementAndGet(); }
        public void recordFail()    { total.incrementAndGet(); failed.incrementAndGet(); }
        public void recordSkip()    { total.incrementAndGet(); skipped.incrementAndGet(); }

        public void printSummary() {
            System.out.printf("%nResults → Total: %d | Pass: %d | Fail: %d | Skip: %d%n",
                    total.get(), passed.get(), failed.get(), skipped.get());
            // Pass rate calculation
            double passRate = total.get() > 0
                    ? (passed.get() * 100.0 / total.get()) : 0;
            System.out.printf("Pass Rate: %.1f%%%n", passRate);
        }
    }

    static void pattern2_TestResultCounters() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PATTERN 2: Test Result Counters");
        System.out.println("=".repeat(60));

        TestResultTracker tracker = new TestResultTracker();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(20);

        String[] tests = {
                "test_createOrder", "test_getOrder", "test_payment",
                "test_cancel", "test_refund", "test_login", "test_logout",
                "test_register", "test_profile", "test_search",
                "test_cart", "test_checkout", "test_review", "test_wishlist",
                "test_address", "test_coupon", "test_notification",
                "test_tracking", "test_invoice", "test_return"
        };

        for (String test : tests) {
            pool.submit(() -> {
                try {
                    Thread.sleep((long)(Math.random() * 30));
                    double rand = Math.random();
                    if (rand > 0.3)      { tracker.recordPass();
                        System.out.println("  PASS: " + test); }
                    else if (rand > 0.1) { tracker.recordFail();
                        System.out.println("  FAIL: " + test); }
                    else                 { tracker.recordSkip();
                        System.out.println("  SKIP: " + test); }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        tracker.printSummary();
    }

    // ================================================================
    // PATTERN 3: Request ID generator — unique ID per API call
    // ================================================================
    static class RequestIdGenerator {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final String prefix;

        RequestIdGenerator(String prefix) { this.prefix = prefix; }

        // ✅ Each call guaranteed unique — even from parallel threads
        public String next() {
            return prefix + "-" + counter.incrementAndGet();
        }
    }

    static void pattern3_RequestIdGenerator() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PATTERN 3: Request ID Generator");
        System.out.println("=".repeat(60));

        RequestIdGenerator idGen = new RequestIdGenerator("REQ");
        Set<String> generatedIds = ConcurrentHashMap.newKeySet(); // thread-safe Set
        AtomicInteger duplicateCount = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(20);

        for (int i = 0; i < 20; i++) {
            pool.submit(() -> {
                String id = idGen.next();
                boolean isNew = generatedIds.add(id);
                if (!isNew) {
                    duplicateCount.incrementAndGet();
                    System.out.println("  ❌ DUPLICATE: " + id);
                } else {
                    System.out.println("  ✅ Generated: " + id
                            + " by Thread-" + Thread.currentThread().getId());
                }
                latch.countDown();
            });
        }

        latch.await();
        pool.shutdown();
        System.out.println("Total generated: " + generatedIds.size()
                + " | Duplicates: " + duplicateCount.get());
    }

    // ================================================================
    // PATTERN 4: Retry counter — per-test retry tracking
    // ================================================================
    static class RetryTracker {
        // Each test gets its own AtomicInteger for retry count
        private final ConcurrentHashMap<String, AtomicInteger> retries
                = new ConcurrentHashMap<>();

        public int incrementRetry(String testName) {
            // computeIfAbsent is atomic — safe to call from parallel threads
            return retries
                    .computeIfAbsent(testName, k -> new AtomicInteger(0))
                    .incrementAndGet();
        }

        public int getRetryCount(String testName) {
            AtomicInteger count = retries.get(testName);
            return count == null ? 0 : count.get();
        }

        public void printRetries() {
            System.out.println("\nRetry Summary:");
            retries.forEach((test, count) ->
                    System.out.printf("  %-30s retried %d times%n",
                            test, count.get()));
        }
    }

    static void pattern4_RetryCounter() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PATTERN 4: Retry Counter");
        System.out.println("=".repeat(60));

        RetryTracker retryTracker = new RetryTracker();
        int MAX_RETRIES = 3;

        String[] flakyTests = {
                "test_payment_gateway",
                "test_kafka_consumer",
                "test_circuit_breaker"
        };

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(flakyTests.length);

        for (String testName : flakyTests) {
            pool.submit(() -> {
                boolean passed = false;
                while (!passed) {
                    int attempt = retryTracker.incrementRetry(testName);
                    try {
                        Thread.sleep(10);
                        // Simulate: passes on 3rd attempt
                        if (attempt >= MAX_RETRIES) {
                            passed = true;
                            System.out.println("  ✅ PASSED: " + testName
                                    + " on attempt " + attempt);
                        } else {
                            System.out.println("  🔄 RETRY: " + testName
                                    + " attempt " + attempt + " failed");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                latch.countDown();
            });
        }

        latch.await();
        pool.shutdown();
        retryTracker.printRetries();
    }

    // ================================================================
    // PATTERN 5: compareAndSet — conditional update
    // This is what makes AtomicInteger truly powerful
    // ================================================================
    static class SuiteController {
        // 0 = running, 1 = aborted
        private final AtomicInteger state = new AtomicInteger(0);
        private final AtomicInteger completedTests = new AtomicInteger(0);
        private final int failThreshold;

        SuiteController(int failThreshold) {
            this.failThreshold = failThreshold;
        }

        // ✅ compareAndSet — only first thread to reach threshold aborts suite
        // All others are ignored — no duplicate abort messages
        public boolean tryAbort(String reason) {
            boolean aborted = state.compareAndSet(0, 1); // only succeeds once
            if (aborted) {
                System.out.println("  🛑 SUITE ABORTED by: " + reason
                        + " | completed so far: " + completedTests.get());
            }
            return aborted;
        }

        public boolean isAborted() { return state.get() == 1; }

        public void recordComplete() { completedTests.incrementAndGet(); }
    }

    static void pattern5_CompareAndSet() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("PATTERN 5: compareAndSet — suite abort on failures");
        System.out.println("=".repeat(60));

        AtomicInteger failCount = new AtomicInteger(0);
        SuiteController suite = new SuiteController(3);

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(10);

        for (int i = 1; i <= 10; i++) {
            final int testNum = i;
            pool.submit(() -> {
                if (suite.isAborted()) {
                    System.out.println("  ⏭  SKIPPED: test_" + testNum
                            + " (suite aborted)");
                    latch.countDown();
                    return;
                }
                try {
                    Thread.sleep((long)(Math.random() * 20));
                    boolean testFailed = testNum % 3 == 0; // tests 3,6,9 fail

                    if (testFailed) {
                        int currentFails = failCount.incrementAndGet();
                        System.out.println("  ❌ FAILED: test_" + testNum
                                + " | total fails: " + currentFails);
                        if (currentFails >= 3) {
                            suite.tryAbort("test_" + testNum);
                        }
                    } else {
                        suite.recordComplete();
                        System.out.println("  ✅ PASSED: test_" + testNum);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
        System.out.println("\nFinal state: "
                + (suite.isAborted() ? "ABORTED" : "COMPLETED")
                + " | fails: " + failCount.get());
    }

    // ================================================================
    // MAIN
    // ================================================================
    public static void main(String[] args) throws InterruptedException {
        pattern1_BasicCounter();
        pattern2_TestResultCounters();
        pattern3_RequestIdGenerator();
        pattern4_RetryCounter();
        pattern5_CompareAndSet();

        System.out.println("\n" + "=".repeat(60));
        System.out.println("SUMMARY: AtomicInteger patterns in automation");
        System.out.println("=".repeat(60));
        System.out.println("  Pattern 1: incrementAndGet()  → test counters");
        System.out.println("  Pattern 2: multiple atomics   → result tracker");
        System.out.println("  Pattern 3: incrementAndGet()  → unique ID gen");
        System.out.println("  Pattern 4: computeIfAbsent    → retry tracker");
        System.out.println("  Pattern 5: compareAndSet()    → suite abort");
    }
}