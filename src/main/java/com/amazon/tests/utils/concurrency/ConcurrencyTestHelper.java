package com.amazon.tests.utils.concurrency;


import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * Generic helpers for coordinating and waiting on concurrent test execution
 * (thread pools, latches) without relying on java.util.concurrent.TimeUnit-based
 * blocking calls directly in test classes.
 */
@Slf4j
public final class ConcurrencyTestHelper {

    private ConcurrencyTestHelper() {}

    /**
     * Wait for an ExecutorService to terminate, polling instead of blocking indefinitely.
     */
    public static void waitForExecutorTermination(ExecutorService executor, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (!executor.isTerminated()) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Executor did not terminate within {}ms, forcing shutdown", timeoutMillis);
                executor.shutdownNow();
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
                break;
            }
        }
    }

    /**
     * Wait for a CountDownLatch to reach zero, polling with a timeout.
     */
    public static void waitForLatch(CountDownLatch latch, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;

        while (latch.getCount() > 0) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("Latch timed out after {}ms", timeoutMillis);
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
