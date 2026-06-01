package com.amazon.tests.multithreadingFailureScenarios.revision;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadSafeReportingDemo {

    // ================================================================
    // SHARED TEST RESULT MODEL
    // ================================================================
    static class TestResult {
        String testName;
        String threadId;
        String status;
        String details;
        long duration;

        TestResult(String testName, String threadId,
                   String status, String details, long duration) {
            this.testName = testName;
            this.threadId = threadId;
            this.status   = status;
            this.details  = details;
            this.duration = duration;
        }

        @Override
        public String toString() {
            return String.format("  %-35s | %-8s | %-6s | %dms | %s",
                    testName, threadId, status, duration, details);
        }
    }

    // ================================================================
    // LEVEL 1: BROKEN REPORTER — no thread safety
    // ================================================================
    static class BrokenReporter {
        private List<TestResult> results = new ArrayList<>(); // ❌ not thread-safe
        private int passCount = 0;                            // ❌ not thread-safe
        private int failCount = 0;                            // ❌ not thread-safe

        public void addResult(TestResult result) {
            // ❌ Two threads can call this simultaneously
            // ArrayList.add() is not atomic — can corrupt internal array
            results.add(result);

            if (result.status.equals("PASS")) passCount++;
            else failCount++;
        }

        public void printReport(String title) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("📊 " + title);
            System.out.println("=".repeat(80));
            System.out.println("Total recorded: " + results.size()
                    + " | Pass: " + passCount + " | Fail: " + failCount);
            results.forEach(System.out::println);
        }
    }

    // ================================================================
    // LEVEL 2: SYNCHRONIZED REPORTER — thread-safe but blocks
    // ================================================================
    static class SynchronizedReporter {
        private final List<TestResult> results = new ArrayList<>();
        private int passCount = 0;
        private int failCount = 0;

        // ✅ synchronized — only one thread can write at a time
        public synchronized void addResult(TestResult result) {
            results.add(result);
            if (result.status.equals("PASS")) passCount++;
            else failCount++;
        }

        public synchronized void printReport(String title) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("📊 " + title);
            System.out.println("=".repeat(80));
            System.out.println("Total recorded: " + results.size()
                    + " | Pass: " + passCount + " | Fail: " + failCount);
            results.forEach(System.out::println);
        }
    }

    // ================================================================
    // LEVEL 3: CONCURRENT REPORTER — thread-safe, non-blocking
    // ================================================================
    static class ConcurrentReporter {
        // ✅ CopyOnWriteArrayList — thread-safe reads, safe concurrent writes
        private final List<TestResult> results = new CopyOnWriteArrayList<>();

        // ✅ AtomicInteger — lock-free thread-safe counters
        private final AtomicInteger passCount = new AtomicInteger(0);
        private final AtomicInteger failCount = new AtomicInteger(0);
        private final AtomicInteger totalCount = new AtomicInteger(0);

        public void addResult(TestResult result) {
            results.add(result);          // ✅ thread-safe, no lock needed
            totalCount.incrementAndGet(); // ✅ atomic, no lock needed

            if (result.status.equals("PASS")) passCount.incrementAndGet();
            else failCount.incrementAndGet();
        }

        public void printReport(String title) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("📊 " + title);
            System.out.println("=".repeat(80));
            System.out.println("Total recorded: " + totalCount.get()
                    + " | Pass: " + passCount.get()
                    + " | Fail: " + failCount.get());
            results.forEach(System.out::println);
        }
    }

    // ================================================================
    // LEVEL 4: ALLURE-STYLE REPORTER — ThreadLocal per test + shared sink
    // This is how Allure actually works internally
    // ================================================================
    static class AllureStyleReporter {

        // Each thread accumulates its OWN test steps locally
        private static final ThreadLocal<List<String>> threadSteps =
                ThreadLocal.withInitial(ArrayList::new);

        private static final ThreadLocal<Long> testStartTime =
                ThreadLocal.withInitial(System::currentTimeMillis);

        // Shared sink — all threads eventually flush here
        private final List<TestResult> results = new CopyOnWriteArrayList<>();
        private final AtomicInteger passCount = new AtomicInteger(0);
        private final AtomicInteger failCount = new AtomicInteger(0);

        // Called by each thread at test START
        public void startTest() {
            threadSteps.get().clear();                          // fresh step list
            testStartTime.set(System.currentTimeMillis());     // record start time
        }

        // Called by each thread to ADD A STEP during test
        public void addStep(String step) {
            threadSteps.get().add(step);  // ✅ writing to THIS thread's list only
        }

        // Called by each thread at test END — flushes to shared sink
        public void finishTest(String testName, String status) {
            long duration = System.currentTimeMillis() - testStartTime.get();
            String steps = String.join(" → ", threadSteps.get());
            String tid = "T-" + Thread.currentThread().getId();

            TestResult result = new TestResult(testName, tid, status, steps, duration);

            // ✅ CopyOnWriteArrayList handles concurrent add safely
            results.add(result);

            if (status.equals("PASS")) passCount.incrementAndGet();
            else failCount.incrementAndGet();

            // ✅ Critical: clean up ThreadLocal after test
            threadSteps.remove();
            testStartTime.remove();
        }

        public void printReport(String title) {
            System.out.println("\n" + "=".repeat(80));
            System.out.println("📊 " + title);
            System.out.println("=".repeat(80));
            System.out.println("Total: " + results.size()
                    + " | Pass: " + passCount.get()
                    + " | Fail: " + failCount.get());
            System.out.println("-".repeat(80));
            System.out.printf("  %-35s | %-8s | %-6s | %s | %s%n",
                    "Test Name", "Thread", "Status", "Time", "Steps");
            System.out.println("-".repeat(80));
            results.forEach(System.out::println);
        }
    }

    // ================================================================
    // SIMULATED TEST EXECUTION
    // ================================================================
    static String simulateTest(String testName, AllureStyleReporter reporter)
            throws InterruptedException {

        reporter.startTest();

        reporter.addStep("Setup");
        Thread.sleep(10);

        reporter.addStep("Execute: " + testName);
        Thread.sleep(15);

        // Simulate 80% pass rate
        String status = Math.random() > 0.2 ? "PASS" : "FAIL";
        reporter.addStep("Verify → " + status);
        Thread.sleep(5);

        reporter.finishTest(testName, status);
        return status;
    }

    // ================================================================
    // MAIN
    // ================================================================
    public static void main(String[] args) throws InterruptedException {

        String[] testNames = {
                "test_createOrder_user1",
                "test_createOrder_user2",
                "test_getOrder_success",
                "test_getOrder_notFound",
                "test_payment_success",
                "test_payment_failure",
                "test_cancelOrder",
                "test_updateOrder"
        };

        // ── DEMO 1: Broken Reporter ──────────────────────────────────
        System.out.println("\n❌ LEVEL 1: BROKEN REPORTER (run multiple times — count changes)");
        for (int run = 1; run <= 3; run++) {
            BrokenReporter broken = new BrokenReporter();
            ExecutorService pool = Executors.newFixedThreadPool(4);
            CountDownLatch latch = new CountDownLatch(testNames.length);

            for (String name : testNames) {
                pool.submit(() -> {
                    try {
                        Thread.sleep((long)(Math.random() * 20));
                        broken.addResult(new TestResult(
                                name,
                                "T-" + Thread.currentThread().getId(),
                                Math.random() > 0.2 ? "PASS" : "FAIL",
                                "steps",
                                30L
                        ));
                    } catch (Exception e) {
                        System.err.println("Broken reporter error: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();
           //    pool.awaitTermination(5, TimeUnit.SECONDS);

            System.out.println("Run " + run + ": expected 8 tests, recorded: "
                    + broken.results.size()
                    + " | pass+fail=" + (broken.passCount + broken.failCount));
        }

        // ── DEMO 2: Synchronized Reporter ───────────────────────────
        System.out.println("\n✅ LEVEL 2: SYNCHRONIZED REPORTER");
        SynchronizedReporter syncReporter = new SynchronizedReporter();
        ExecutorService pool2 = Executors.newFixedThreadPool(4);
        CountDownLatch latch2 = new CountDownLatch(testNames.length);

        for (String name : testNames) {
            pool2.submit(() -> {
                try {
                    Thread.sleep((long)(Math.random() * 20));
                    syncReporter.addResult(new TestResult(
                            name,
                            "T-" + Thread.currentThread().getId(),
                            Math.random() > 0.2 ? "PASS" : "FAIL",
                            "steps",
                            30L
                    ));
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                } finally {
                    latch2.countDown();
                }
            });
        }
        latch2.await();
        pool2.shutdown();
        syncReporter.printReport("Synchronized Reporter — always 8 tests");

        // ── DEMO 3: Concurrent Reporter ──────────────────────────────
        System.out.println("\n✅ LEVEL 3: CONCURRENT REPORTER (non-blocking)");
        ConcurrentReporter concReporter = new ConcurrentReporter();
        ExecutorService pool3 = Executors.newFixedThreadPool(4);
        CountDownLatch latch3 = new CountDownLatch(testNames.length);

        for (String name : testNames) {
            pool3.submit(() -> {
                try {
                    Thread.sleep((long)(Math.random() * 20));
                    concReporter.addResult(new TestResult(
                            name,
                            "T-" + Thread.currentThread().getId(),
                            Math.random() > 0.2 ? "PASS" : "FAIL",
                            "steps",
                            30L
                    ));
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                } finally {
                    latch3.countDown();
                }
            });
        }
        latch3.await();
        pool3.shutdown();
        concReporter.printReport("Concurrent Reporter — always 8 tests, no locks");

        // ── DEMO 4: Allure-Style Reporter ────────────────────────────
        System.out.println("\n✅ LEVEL 4: ALLURE-STYLE REPORTER (ThreadLocal steps + shared sink)");
        AllureStyleReporter allureReporter = new AllureStyleReporter();
        ExecutorService pool4 = Executors.newFixedThreadPool(4);
        CountDownLatch latch4 = new CountDownLatch(testNames.length);

        for (String name : testNames) {
            pool4.submit(() -> {
                try {
                    simulateTest(name, allureReporter);
                } catch (Exception e) {
                    System.err.println(e.getMessage());
                } finally {
                    latch4.countDown();
                }
            });
        }
        latch4.await();
        pool4.shutdown();
        allureReporter.printReport("Allure-Style Reporter — steps isolated per thread");
    }
}