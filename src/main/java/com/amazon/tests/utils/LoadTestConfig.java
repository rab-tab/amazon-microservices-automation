package com.amazon.tests.utils;

/**
 * Configuration for Producer-Consumer Load Tests
 *
 * Centralized configuration management for:
 * - Thread counts
 * - Queue settings
 * - API endpoints
 * - Test data volumes
 */
public class LoadTestConfig {

    // ==========================================
    // API Configuration
    // ==========================================
    public static final String BASE_URL = getEnvOrDefault("BASE_URL", "http://localhost:8080");
    public static final String USER_API_ENDPOINT = "/api/users";
    public static final String PRODUCT_API_ENDPOINT = "/api/products";
    public static final String ORDER_API_ENDPOINT = "/api/orders";

    // ==========================================
    // Load Test Configuration
    // ==========================================

    // Small Load Test (for local testing)
    public static final int SMALL_TEST_ORDERS = 100;
    public static final int SMALL_TEST_PRODUCERS = 2;
    public static final int SMALL_TEST_CONSUMERS = 5;

    // Medium Load Test
    public static final int MEDIUM_TEST_ORDERS = 1000;
    public static final int MEDIUM_TEST_PRODUCERS = 3;
    public static final int MEDIUM_TEST_CONSUMERS = 20;

    // Large Load Test (for performance testing)
    public static final int LARGE_TEST_ORDERS = 10000;
    public static final int LARGE_TEST_PRODUCERS = 5;
    public static final int LARGE_TEST_CONSUMERS = 50;

    // ==========================================
    // Queue Configuration
    // ==========================================

    /**
     * Queue capacity formula:
     * consumerThreadCount * 5 (allows some buffering)
     */
    public static int getQueueCapacity(int consumerCount) {
        return consumerCount * 5;
    }

    // ==========================================
    // Thread Pool Configuration
    // ==========================================

    /**
     * Calculate optimal producer count
     * Rule: Fewer producers than consumers (data generation is slower)
     */
    public static int getOptimalProducerCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return Math.max(2, cores / 2);  // 50% of cores
    }

    /**
     * Calculate optimal consumer count
     * Rule: More consumers for I/O-bound tests (API calls)
     */
    public static int getOptimalConsumerCount() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores * 3;  // 3x cores for I/O-bound work
    }

    // ==========================================
    // Performance Tuning
    // ==========================================

    // Delays (in milliseconds)
    public static final int PRODUCER_DELAY_MS = 50;  // Delay between data generation
    public static final int CONSUMER_DELAY_MS = 0;   // No delay for consumers

    // Timeouts
    public static final int API_TIMEOUT_SECONDS = 30;
    public static final int PRODUCER_TIMEOUT_MINUTES = 30;
    public static final int CONSUMER_TIMEOUT_MINUTES = 30;

    // ==========================================
    // Test Data Configuration
    // ==========================================

    // Product price range
    public static final double MIN_PRODUCT_PRICE = 10.00;
    public static final double MAX_PRODUCT_PRICE = 100.00;

    // Order quantity range
    public static final int MIN_ORDER_QUANTITY = 1;
    public static final int MAX_ORDER_QUANTITY = 5;

    // ==========================================
    // Success Criteria
    // ==========================================

    // Minimum acceptable success rate
    public static final double MIN_SUCCESS_RATE = 95.0;

    // Maximum acceptable failure rate
    public static final double MAX_FAILURE_RATE = 5.0;

    // ==========================================
    // Logging Configuration
    // ==========================================

    // Log progress every N orders
    public static final int LOG_PROGRESS_INTERVAL = 10;

    // Print summary every N orders
    public static final int SUMMARY_INTERVAL = 100;

    // ==========================================
    // Retry Configuration
    // ==========================================

    // Max retries for failed API calls
    public static final int MAX_RETRIES = 3;

    // Retry delay (milliseconds)
    public static final int RETRY_DELAY_MS = 1000;

    // ==========================================
    // Helper Methods
    // ==========================================

    /**
     * Get environment variable or default value
     */
    private static String getEnvOrDefault(String envVar, String defaultValue) {
        String value = System.getenv(envVar);
        return (value != null && !value.isEmpty()) ? value : defaultValue;
    }

    /**
     * Get load test configuration by name
     */
    public static LoadTestProfile getProfile(String profileName) {
        switch (profileName.toLowerCase()) {
            case "small":
                return new LoadTestProfile(
                        SMALL_TEST_ORDERS,
                        SMALL_TEST_PRODUCERS,
                        SMALL_TEST_CONSUMERS,
                        getQueueCapacity(SMALL_TEST_CONSUMERS)
                );

            case "medium":
                return new LoadTestProfile(
                        MEDIUM_TEST_ORDERS,
                        MEDIUM_TEST_PRODUCERS,
                        MEDIUM_TEST_CONSUMERS,
                        getQueueCapacity(MEDIUM_TEST_CONSUMERS)
                );

            case "large":
                return new LoadTestProfile(
                        LARGE_TEST_ORDERS,
                        LARGE_TEST_PRODUCERS,
                        LARGE_TEST_CONSUMERS,
                        getQueueCapacity(LARGE_TEST_CONSUMERS)
                );

            case "auto":
                int producers = getOptimalProducerCount();
                int consumers = getOptimalConsumerCount();
                return new LoadTestProfile(
                        MEDIUM_TEST_ORDERS,
                        producers,
                        consumers,
                        getQueueCapacity(consumers)
                );

            default:
                return new LoadTestProfile(
                        MEDIUM_TEST_ORDERS,
                        MEDIUM_TEST_PRODUCERS,
                        MEDIUM_TEST_CONSUMERS,
                        getQueueCapacity(MEDIUM_TEST_CONSUMERS)
                );
        }
    }

    /**
     * Load test profile data structure
     */
    public static class LoadTestProfile {
        public final int totalOrders;
        public final int producerCount;
        public final int consumerCount;
        public final int queueCapacity;

        public LoadTestProfile(int totalOrders, int producerCount,
                               int consumerCount, int queueCapacity) {
            this.totalOrders = totalOrders;
            this.producerCount = producerCount;
            this.consumerCount = consumerCount;
            this.queueCapacity = queueCapacity;
        }

        @Override
        public String toString() {
            return String.format(
                    "LoadTestProfile[orders=%d, producers=%d, consumers=%d, queueSize=%d]",
                    totalOrders, producerCount, consumerCount, queueCapacity
            );
        }
    }

    /**
     * Print current configuration
     */
    public static void printConfiguration(LoadTestProfile profile) {
        System.out.println("=".repeat(70));
        System.out.println("LOAD TEST CONFIGURATION");
        System.out.println("=".repeat(70));
        System.out.println("API Configuration:");
        System.out.println("  Base URL: " + BASE_URL);
        System.out.println("  User Endpoint: " + USER_API_ENDPOINT);
        System.out.println("  Product Endpoint: " + PRODUCT_API_ENDPOINT);
        System.out.println("  Order Endpoint: " + ORDER_API_ENDPOINT);
        System.out.println("\nLoad Test Profile:");
        System.out.println("  Total Orders: " + profile.totalOrders);
        System.out.println("  Producers: " + profile.producerCount);
        System.out.println("  Consumers: " + profile.consumerCount);
        System.out.println("  Queue Capacity: " + profile.queueCapacity);
        System.out.println("\nPerformance Settings:");
        System.out.println("  Producer Delay: " + PRODUCER_DELAY_MS + "ms");
        System.out.println("  API Timeout: " + API_TIMEOUT_SECONDS + "s");
        System.out.println("\nSuccess Criteria:");
        System.out.println("  Min Success Rate: " + MIN_SUCCESS_RATE + "%");
        System.out.println("  Max Failure Rate: " + MAX_FAILURE_RATE + "%");
        System.out.println("=".repeat(70));
    }
}
