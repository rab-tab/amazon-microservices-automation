// BaseTest.java
package com.amazon.tests.tests;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.config.ExtentReportManager;
import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.dataseeding.cleanup.CleanupManager;
import com.amazon.tests.listeners.AllureTestListener;
import com.amazon.tests.utils.DatabaseValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import lombok.extern.slf4j.Slf4j;
import org.aeonbits.owner.ConfigFactory;
import org.testng.annotations.*;

import java.util.UUID;

/**
 * Base class for all tests
 * Provides:
 * - Configuration management
 * - Database validation
 * - Extent reporting
 * - Data seeding framework (SeedingContext + CleanupManager)
 */
@Slf4j
@Listeners(AllureTestListener.class)
public abstract class BaseTest {

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

    // ==========================================
    // SUITE SETUP
    // ==========================================

    @BeforeSuite(alwaysRun = true)
    public void setupSuite() {
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
        log.info("┌────────────────────────────────────────────────────────┐");
        log.info("│ Test Method Cleanup                                    │");
        log.info("└────────────────────────────────────────────────────────┘");

        // ✅ Execute data cleanup (LIFO order)
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
    public void tearDownSuite() {
        log.info("════════════════════════════════════════════════════════════");
        log.info("Shutting down test suite...");

        // Shutdown database connection pools
        DatabaseValidator.getInstance().shutdown();

        // Flush Extent Reports
        ExtentReportManager.getInstance().flush();

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

    /**
     * Log seeding statistics
     */
    protected void logSeedingStats() {
        if (context != null && !context.getSeedingStats().isEmpty()) {
            log.info("Seeding Statistics: {}", context.getStats());
        }
    }
}