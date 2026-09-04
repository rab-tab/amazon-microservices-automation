package com.amazon.tests;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.auth.NoAuthStrategy;
import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.reports.ExtentReportManager;
import com.amazon.tests.reports.TestReporter;
import com.amazon.tests.reports.TestReporterFactory;
import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.config.restAsssured.RestClient;
import com.amazon.tests.dataseeding.cleanup.CleanupManager;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.transport.RestHttpClient;
import com.amazon.tests.utils.metrics.MetricsHttpServer;
import com.amazon.tests.utils.metrics.MetricsSupport;
import com.amazon.tests.utils.retry.RetryHandler;
import com.amazon.tests.utils.validators.DatabaseValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.aeonbits.owner.ConfigFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base class for all tests.
 * Owns: suite-wide transport wiring, per-method seeding/cleanup lifecycle,
 * and small logging/wait helpers.
 * Delegates: retry policy → RetryPresets, instrumentation → MetricsSupport.
 */
@Slf4j
public abstract class BaseTest {

    // ==========================================
    // SHARED SUITE INFRASTRUCTURE
    // ==========================================

    public static RestClient restClient;
    public static RestAssuredConfig restAssuredConfig;
    public static RequestExecutor executor;
    public static AuthStrategy authStrategy;
    protected static TestConfig testConfig;
    protected TestReporter reporter = TestReporterFactory.create();

    protected static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static MetricsHttpServer metricsServer;

    // ==========================================
    // PER-METHOD STATE
    // ==========================================

    protected SeedingContext context;
    protected CleanupManager cleanupManager;
    private long testStart;

    // ==========================================
    // SUITE SETUP
    // ==========================================

    @BeforeSuite(alwaysRun = true)
    public void setupSuite() throws Exception {
        log.info("Initializing test suite");

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        DatabaseValidator.getInstance();
        ExtentReportManager.getInstance();

        String env = System.getProperty("env", "local");
        System.setProperty("env", env);
        testConfig = ConfigFactory.create(TestConfig.class);

        restClient = new RestClient();
        restAssuredConfig = new RestAssuredConfig(testConfig);
        executor = new RestHttpClient(restClient, restAssuredConfig);
        authStrategy = new NoAuthStrategy();

        if (metricsServer == null) {
            metricsServer = new MetricsHttpServer();
            metricsServer.start();
        }

        System.setProperty("spring.profiles.active", "test");
        System.setProperty("order.idempotency.ttl-seconds", "5");

        log.info("Environment: {} | Base URL: {} | User: {} | Product: {} | Order: {}",
                env,
                ConfigManager.getInstance().getBaseUrl(),
                ConfigManager.getInstance().getUserServiceUrl(),
                ConfigManager.getInstance().getProductServiceUrl(),
                ConfigManager.getInstance().getOrderServiceUrl());

        log.info("Test suite setup complete");
    }

    // ==========================================
    // METHOD SETUP / TEARDOWN (seeding lifecycle)
    // ==========================================

    @BeforeMethod(alwaysRun = true)
    public void setupTestMethod() {
        String namespace = generateNamespace();

        context = new SeedingContext(namespace, testConfig, executor);
        cleanupManager = new CleanupManager(context);
        testStart = System.currentTimeMillis();

        log.info("Test started: {} | namespace: {}", this.getClass().getSimpleName(), namespace);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupTestMethod() {
        MetricsSupport.recordTestDuration(System.currentTimeMillis() - testStart);
        MetricsSupport.pushToPrometheus("automation-suite");
        ExtentReportManager.getInstance().removeTest();
        if (cleanupManager != null) {
            try {
                cleanupManager.executeCleanup();
            } catch (Exception e) {
                log.warn("Error during cleanup: {}", e.getMessage(), e);
            }
        }

        log.info("Test method cleanup complete");
    }

    @AfterSuite(alwaysRun = true)
    public void tearDownSuite() throws InterruptedException {
        log.info("Shutting down test suite...");

        DatabaseValidator.getInstance().shutdown();
        ExtentReportManager.getInstance().flush();
        MetricsSupport.pushToPrometheus("amazon-automation-framework");

        Thread.sleep(5000);
        log.info("Test suite shutdown complete");
    }

    // ==========================================
    // HELPERS
    // ==========================================

    private String generateNamespace() {
        return "test_" + System.currentTimeMillis() + "_" +
                UUID.randomUUID().toString().substring(0, 8);
    }

    protected void logStep(String step) {
        log.info("STEP: {}", step);
    }

   /* protected void logStep(String message, Object... args) {
        String formatted = formatMessage(message, args);
        log.info("STEP: {}", formatted);
        try {
            Allure.step(formatted);
        } catch (Exception e) {
            // Allure not available - ignore
        }
    }*/

    protected void logStep(String message, Object... args) {
        reporter.logStep(formatMessage(message, args));
    }

    private String formatMessage(String message, Object... args) {
        if (args == null || args.length == 0) return message;
        String result = message;
        for (Object arg : args) {
            result = result.replaceFirst("\\{\\}", String.valueOf(arg));
        }
        return result;
    }

    protected void waitForDataPropagation(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Wait interrupted", e);
        }
    }

    protected void logSeedingStats() {
        if (context != null && !context.getSeedingStats().isEmpty()) {
            log.info("Seeding Statistics: {}", context.getStats());
        }
    }

    // ==========================================
    // RETRY — thin pass-through to RetryHandler.
    // Policy presets live in RetryPresets, not here.
    // ==========================================

    protected Response executeWithRetry(Supplier<Response> request) {
        return RetryHandler.executeRequestWithRetry(request);
    }

    protected Response executeWithRetry(Supplier<Response> request, RetryHandler.RetryConfig config) {
        return RetryHandler.executeRequestWithRetry(request, config);
    }

    protected Response executeWithRetry(String endpoint, Supplier<Response> request) {
        return MetricsSupport.timedRequest(endpoint, () -> RetryHandler.executeRequestWithRetry(request));
    }
}