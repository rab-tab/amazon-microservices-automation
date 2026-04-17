package com.amazon.tests.utils;


import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

/**
 * Utility class for handling timeouts without using TimeUnit
 * Provides alternatives for common timeout operations in tests
 */
public class TimeoutHelper {

    /**
     * Wait for CountDownLatch with timeout
     *
     * @param latch The latch to wait for
     * @param timeoutMillis Timeout in milliseconds
     * @return true if latch reached zero, false if timeout
     * @throws InterruptedException if thread is interrupted
     */
    public static boolean awaitLatch(CountDownLatch latch, long timeoutMillis) throws InterruptedException {
        long waitStart = System.currentTimeMillis();
        while (latch.getCount() > 0 && (System.currentTimeMillis() - waitStart) < timeoutMillis) {
            Thread.sleep(100); // Check every 100ms
        }
        return latch.getCount() == 0;
    }

    /**
     * Wait for ExecutorService to terminate with timeout
     *
     * @param executor The executor service
     * @param timeoutMillis Timeout in milliseconds
     * @return true if terminated, false if timeout
     * @throws InterruptedException if thread is interrupted
     */
    public static boolean awaitTermination(ExecutorService executor, long timeoutMillis) throws InterruptedException {
        long shutdownStart = System.currentTimeMillis();
        while (!executor.isTerminated() && (System.currentTimeMillis() - shutdownStart) < timeoutMillis) {
            Thread.sleep(100); // Check every 100ms
        }
        return executor.isTerminated();
    }

    /**
     * Sleep for specified milliseconds
     * Wrapper around Thread.sleep for consistency
     *
     * @param millis Milliseconds to sleep
     */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Sleep interrupted", e);
        }
    }

    /**
     * Sleep for specified seconds
     *
     * @param seconds Seconds to sleep
     */
    public static void sleepSeconds(int seconds) {
        sleep(seconds * 1000L);
    }

    /**
     * Common timeout constants (in milliseconds)
     */
    public static class Timeouts {
        public static final long ONE_SECOND = 1000;
        public static final long FIVE_SECONDS = 5000;
        public static final long TEN_SECONDS = 10000;
        public static final long THIRTY_SECONDS = 30000;
        public static final long ONE_MINUTE = 60000;
    }
}