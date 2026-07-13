package com.amazon.tests.config;


public class RetryPolicy {

    private final int maxAttempts;
    private final long retryIntervalMillis;

    public RetryPolicy(int maxAttempts, long retryIntervalMillis) {
        this.maxAttempts = maxAttempts;
        this.retryIntervalMillis = retryIntervalMillis;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public long getRetryIntervalMillis() {
        return retryIntervalMillis;
    }

    public boolean shouldRetry(int currentAttempt, Exception exception) {

        if (currentAttempt >= maxAttempts) {
            return false;
        }

        return isTransient(exception);
    }

    private boolean isTransient(Exception exception) {

        String message = exception.getMessage();

        if (message == null) {
            return false;
        }

        message = message.toLowerCase();

        return message.contains("timeout")
                || message.contains("deadlock")
                || message.contains("connection")
                || message.contains("network")
                || message.contains("temporarily");
    }
}
