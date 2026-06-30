// BaseTest.java
package com.amazon.tests;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.config.ExtentReportManager;
import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.dataseeding.cleanup.CleanupManager;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.utils.metrics.MetricsHttpServer;
import com.amazon.tests.utils.metrics.MetricsManager;
import com.amazon.tests.utils.metrics.MetricsPushService;
import com.amazon.tests.utils.metrics.MetricsReporter;
import com.amazon.tests.utils.retry.RetryHandler;
import com.amazon.tests.utils.validators.DatabaseValidator;
import com.epam.reportportal.testng.ReportPortalTestNGListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.management.OperatingSystemMXBean;
import io.qameta.allure.Allure;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.aeonbits.owner.ConfigFactory;
import org.testng.annotations.*;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base class for all tests
 * Provides:
 * - Configuration management
 * - Database validation
 * - Extent reporting
 * - Data seeding framework (SeedingContext + CleanupManager)
 */
@Slf4j
@Listeners(ReportPortalTestNGListener.class)
public abstract class BaseTest {

    protected long testStartTime;
    protected long testCpuStart;
    private long testStart;
    private long wallStart;
    private long cpuStart;
    private OperatingSystemMXBean osBean;
    private static MetricsHttpServer metricsServer;

    private final MetricsManager metrics =
            MetricsManager.getInstance();
    // ==========================================
    // EXISTING COMPONENTS (Unchanged)
    // ==========================================

    protected static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    // ==========================================
    // DATA SEEDING COMPONENTS (New)
    // ==========================================

    /**
     * Owner-based TestConfig for data seeding
     * Uses same configuration as ConfigManager
     */
    protected static TestConfig testConfig;

    /**
     * Seeding context - unique per test method
     * Provides RestClient, RestAssuredConfig, and cleanup tracking
     */
    protected SeedingContext context;

    /**
     * Cleanup manager - executes cleanup tasks after each test
     */
    protected CleanupManager cleanupManager;

    //protected TestMetrics metrics;
    // ==========================================
    // SUITE SETUP
    // ==========================================

    @BeforeSuite(alwaysRun = true)
    public void setupSuite() throws Exception {
        log.info("╔════════════════════════════════════════════════════════════╗");
        log.info("║          Initializing Test Suite                          ║");
        log.info("╚════════════════════════════════════════════════════════════╝");

        // Initialize RestAssured
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        // Initialize DatabaseValidator (creates connection pools)
        DatabaseValidator.getInstance();

        // Initialize ExtentReportManager (creates report)
        ExtentReportManager.getInstance();

        // ✅ Initialize TestConfig for data seeding (Owner library)
        String env = System.getProperty("env", "local");
        System.setProperty("env", env);
        testConfig = ConfigFactory.create(TestConfig.class);
        if (metricsServer == null) {

            metricsServer =
                    new MetricsHttpServer();

            metricsServer.start();
        }

        // ════════════════════════════════════════════════════════════
        // ⭐ STEP 2: Configure Spring Boot Services (for testing)
        // ════════════════════════════════════════════════════════════
        System.setProperty("spring.profiles.active", "test");
        System.setProperty("order.idempotency.ttl-seconds", "5");

        log.info("🧪 Spring Profile: test");
        log.info("⏱️  Idempotency TTL: 5 seconds");

        // Log configuration
        log.info("┌────────────────────────────────────────────────────────┐");
        log.info("│ Configuration Details                                  │");
        log.info("├────────────────────────────────────────────────────────┤");
        log.info("│ Environment: {}", env);
        log.info("│ Base URL: {}", ConfigManager.getInstance().getBaseUrl());
        log.info("│ User Service: {}", ConfigManager.getInstance().getUserServiceUrl());
        log.info("│ Product Service: {}", ConfigManager.getInstance().getProductServiceUrl());
        log.info("│ Order Service: {}", ConfigManager.getInstance().getOrderServiceUrl());
        log.info("└────────────────────────────────────────────────────────┘");

        log.info("✓ Test suite setup complete");
        log.info("════════════════════════════════════════════════════════════");
    }

    // ==========================================
    // METHOD SETUP (Data Seeding Initialization)
    // ==========================================

    /**
     * Initialize seeding context before each test method
     * Creates unique namespace for test isolation
     */
    @BeforeMethod(alwaysRun = true)
    public void setupTestMethod() {
        // Generate unique namespace for test isolation
        String namespace = generateNamespace();

        // ✅ Initialize seeding context
        context = new SeedingContext(namespace, testConfig);
        testStart = System.currentTimeMillis();

        // ✅ Initialize cleanup manager
        cleanupManager = new CleanupManager(context);
        log.info("┌────────────────────────────────────────────────────────┐");
        log.info("│ Test Method Started                                    │");
        log.info("├────────────────────────────────────────────────────────┤");
        log.info("│ Test Class: {}", this.getClass().getSimpleName());
        log.info("│ Namespace: {}", namespace);
        log.info("└────────────────────────────────────────────────────────┘");
    }

    // ==========================================
    // METHOD CLEANUP
    // ==========================================

    /**
     * Clean up after each test method
     * - Execute data cleanup (orders → products → users)
     * - Clear RestAssured ThreadLocal cache
     */
    @AfterMethod(alwaysRun = true)
    public void cleanupTestMethod() {
       /* long wallTime =
                System.currentTimeMillis()
                        - wallStart;

        long cpuTimeMs =
                TimeUnit.NANOSECONDS.toMillis(
                        osBean.getProcessCpuTime()
                                - cpuStart);

        double cpuRatio =
                (cpuTimeMs * 100.0)
                        / wallTime;

        double ioRatio =
                100 - cpuRatio;

        String classification =
                cpuRatio > 50
                        ? "CPU_BOUND"
                        : "IO_BOUND";
        log.info("""
                        ===== TEST PROFILE =====

                        Wall Time={}ms

                        CPU Time={}ms

                        CPU Ratio={}%

                        IO Ratio={}%

                        Classification={}
                        """,
                wallTime,
                cpuTimeMs,
                String.format("%.2f", cpuRatio),
                String.format("%.2f", ioRatio),
                classification
        );*/
        log.info("┌────────────────────────────────────────────────────────┐");
        log.info("│ Test Method Cleanup                                    │");
        log.info("└────────────────────────────────────────────────────────┘");

        metrics.recordTestDuration(
                System.currentTimeMillis()
                        - testStart);

        MetricsPushService.pushToPrometheus(
                "automation-suite");
        // ✅ Execute data cleanup (LIFO order)
        MetricsReporter.print();
        if (cleanupManager != null) {
            try {
                cleanupManager.executeCleanup();
            } catch (Exception e) {
                log.warn("Error during cleanup: {}", e.getMessage(), e);
            }
        }

        // ✅ Clear RestAssured ThreadLocal to prevent memory leaks
        RestAssuredConfig.clearCache();

        log.info("✓ Test method cleanup complete");
    }

    // ==========================================
    // SUITE TEARDOWN
    // ==========================================

    @AfterSuite(alwaysRun = true)
    public void tearDownSuite() throws InterruptedException {
        log.info("════════════════════════════════════════════════════════════");
        log.info("Shutting down test suite...");

        // Shutdown database connection pools
        DatabaseValidator.getInstance().shutdown();

        // Flush Extent Reports
        ExtentReportManager.getInstance().flush();

        MetricsPushService.pushToPrometheus("amazon-automation-framework");
        Thread.sleep(5000);
        log.info("✓ Test suite shutdown complete");
        log.info("════════════════════════════════════════════════════════════");
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    /**
     * Generate unique namespace for test isolation
     * Format: test_<timestamp>_<uuid>
     */
    private String generateNamespace() {
        return "test_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Log test step (existing method - unchanged)
     */
    protected void logStep(String step) {
        log.info("→ STEP: {}", step);
    }

    /**
     * Log a test step with formatted message (supports {} placeholders)
     * <p>
     * Examples:
     * logStep("Order created: {}", orderId);
     * logStep("User {} has {} orders", username, orderCount);
     * logStep("Found {} events in {}ms", count, duration);
     */
    protected void logStep(String message, Object... args) {
        String formattedMessage = formatMessage(message, args);
        log.info("→ STEP: " + formattedMessage);

        try {
            Allure.step(formattedMessage);
        } catch (Exception e) {
            // Allure not available - ignore
        }
    }

    /**
     * Format message with {} placeholders (SLF4J style)
     */
    private String formatMessage(String message, Object... args) {
        if (args == null || args.length == 0) {
            return message;
        }

        String result = message;
        for (Object arg : args) {
            result = result.replaceFirst("\\{\\}", String.valueOf(arg));
        }
        return result;
    }

    /**
     * Wait for data propagation (useful for eventual consistency)
     */
    protected void waitForDataPropagation(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Wait interrupted", e);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // RETRY UTILITIES
    // ══════════════════════════════════════════════════════════════════

    /**
     * Execute HTTP request with default retry logic
     * Retries on: 404, 408, 429, 500, 502, 503, 504
     * Max attempts: 3
     * Policy: Exponential backoff (100ms, 200ms, 400ms)
     */
    protected Response executeWithRetry(Supplier<Response> request) {
        long start = System.currentTimeMillis();

        try {
            return RetryHandler.executeRequestWithRetry(request);
        }
        finally {

          /*  metrics.recordApiCall(
                    System.currentTimeMillis() - start
            );*/
        }
    }

    protected Response executeWithRetry(
            String endpoint,
            Supplier<Response> request) {

        long start =
                System.currentTimeMillis();

        try {

            return RetryHandler.executeRequestWithRetry(request);

        } finally {

            MetricsManager.recordApiLatency(
                    endpoint,
                    System.currentTimeMillis() - start
            );
        }
    }

    protected void measuredAwait(
            String operation,
            Runnable waitLogic) {

        long start =
                System.currentTimeMillis();

        try {

            waitLogic.run();

        } finally {

            MetricsManager.recordAwaitility(
                    operation,
                    System.currentTimeMillis() - start
            );
        }
    }

    protected void measureAwaitility(
            Runnable runnable) {

        long start =
                System.currentTimeMillis();

        try {
            runnable.run();
        }
        finally {

            metrics.recordAwaitility("awaitility",
                    System.currentTimeMillis()
                            - start);
        }
    }

    /**
     * Execute HTTP request with custom retry configuration
     */
    protected Response executeWithRetry(Supplier<Response> request, RetryHandler.RetryConfig config) {
        return RetryHandler.executeRequestWithRetry(request, config);
    }

    /**
     * Create retry config for race condition tests
     * Retries aggressively on 404 (order not found yet)
     */
    protected RetryHandler.RetryConfig raceConditionRetryConfig() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(10)  // More attempts for race conditions
                .initialDelay(100)
                .retryPolicy(RetryHandler.RetryPolicy.LINEAR)
                .retryOnStatusCodes(404, 503)
                .build();
    }

    /**
     * Create retry config for transient failures
     * Retries on common transient errors
     */
    protected RetryHandler.RetryConfig transientFailureRetryConfig() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(5)
                .initialDelay(200)
                .retryPolicy(RetryHandler.RetryPolicy.EXPONENTIAL_BACKOFF)
                .retryOnStatusCodes(408, 429, 500, 502, 503, 504)
                .build();
    }

    /**
     * Create retry config without logging (for high-volume tests)
     */
    protected RetryHandler.RetryConfig silentRetryConfig() {
        return new RetryHandler.RetryConfig()
                .maxAttempts(3)
                .initialDelay(100)
                .disableLogging()
                .build();
    }


    /**
     * Log seeding statistics
     */
    protected void logSeedingStats() {
        if (context != null && !context.getSeedingStats().isEmpty()) {
            log.info("Seeding Statistics: {}", context.getStats());
        }
    }




}