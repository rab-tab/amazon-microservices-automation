package com.amazon.tests.utils;


import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.testng.ITestResult;
import org.testng.annotations.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-Safe Base Test Class for Amazon Microservices Automation
 *
 * Provides thread-safe infrastructure for parallel test execution:
 * - ThreadLocal RestAssured instances
 * - Database connection pooling (HikariCP)
 * - Thread-local test context
 * - Thread-safe entity tracking for cleanup
 * - Atomic metrics collection
 *
 * Usage:
 * public class OrderServiceTests extends ThreadSafeBaseTest {
 *     @Test(threadPoolSize = 10, invocationCount = 100)
 *     public void testOrderCreation() {
 *         TestContext ctx = getContext();
 *         ctx.userId = TestDataGenerator.generateUserId();
 *
 *         getRequestSpec()
 *             .body(createOrderJson(ctx.userId))
 *             .post("/api/orders")
 *             .then()
 *             .statusCode(201);
 *
 *         registerEntity("order", ctx.orderId);
 *     }
 * }
 *
 * @author Amazon Automation Team
 * @version 1.0
 */
public abstract class ThreadSafeBaseTest {

    // ==========================================
    // CONFIGURATION
    // ==========================================

    /**
     * Base URL for API endpoints
     * Override this in subclasses or set via system property
     */
    protected static String getBaseUrl() {
        return System.getProperty("base.url", "http://localhost:8080");
    }

    /**
     * Database connection string
     */
    protected static String getDatabaseUrl() {
        return System.getProperty("db.url", "jdbc:postgresql://localhost:5432/amazon_test");
    }

    /**
     * Database username
     */
    protected static String getDatabaseUser() {
        return System.getProperty("db.user", "test_user");
    }

    /**
     * Database password
     */
    protected static String getDatabasePassword() {
        return System.getProperty("db.password", "test_pass");
    }

    /**
     * Connection pool size (should be >= max thread count)
     */
    protected static int getConnectionPoolSize() {
        return Integer.parseInt(System.getProperty("pool.size", "30"));
    }

    // ==========================================
    // THREAD-LOCAL INSTANCES
    // ==========================================

    /**
     * Thread-local RestAssured RequestSpecification
     * Each thread gets its own instance to avoid conflicts
     */
    private static ThreadLocal<RequestSpecification> requestSpec =
            ThreadLocal.withInitial(() ->
                    RestAssured.given()
                            .baseUri(getBaseUrl())
                            .contentType("application/json")
                            .log().ifValidationFails()
            );

    /**
     * Thread-local test context
     * Stores test-specific data for current thread
     */
    private static ThreadLocal<TestContext> testContext =
            ThreadLocal.withInitial(TestContext::new);

    // ==========================================
    // DATABASE CONNECTION POOLING
    // ==========================================

    /**
     * HikariCP connection pool (thread-safe)
     * Shared across all threads, but each thread gets its own connection
     */
    private static HikariDataSource dataSource;

    // ==========================================
    // THREAD-SAFE COLLECTIONS
    // ==========================================

    /**
     * Track created entities for cleanup
     * Outer Map: Thread ID -> Inner Map (Entity Type:ID -> Entity ID)
     * Example: {15 -> {"user:user123" -> "user123", "order:ORD-1" -> "ORD-1"}}
     */
    private static ConcurrentHashMap<Long, ConcurrentHashMap<String, String>>
            createdEntities = new ConcurrentHashMap<>();

    /**
     * Global test metrics (atomic counters)
     */
    private static AtomicInteger totalTests = new AtomicInteger(0);
    private static AtomicInteger passedTests = new AtomicInteger(0);
    private static AtomicInteger failedTests = new AtomicInteger(0);
    private static AtomicInteger skippedTests = new AtomicInteger(0);

    // ==========================================
    // SUITE-LEVEL SETUP & TEARDOWN
    // ==========================================

    /**
     * Suite-level setup - runs once before all tests
     * Initializes:
     * - Database connection pool
     * - RestAssured configuration
     */
    @BeforeSuite(alwaysRun = true)
    public void setupSuite() {
        System.out.println("=".repeat(70));
        System.out.println("INITIALIZING THREAD-SAFE TEST INFRASTRUCTURE");
        System.out.println("=".repeat(70));

        // Initialize database connection pool
        initializeDatabasePool();

        // Configure RestAssured
        configureRestAssured();

        System.out.println("✅ Base URL: " + getBaseUrl());
        System.out.println("✅ Database Pool Size: " + getConnectionPoolSize());
        System.out.println("✅ Thread-Safe Infrastructure Ready");
        System.out.println("=".repeat(70) + "\n");
    }

    /**
     * Initialize HikariCP database connection pool
     */
    private void initializeDatabasePool() {
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(getDatabaseUrl());
            config.setUsername(getDatabaseUser());
            config.setPassword(getDatabasePassword());

            // Pool configuration
            config.setMaximumPoolSize(getConnectionPoolSize());
            config.setMinimumIdle(10);
            config.setConnectionTimeout(30000);      // 30 seconds
            config.setIdleTimeout(600000);           // 10 minutes
            config.setMaxLifetime(1800000);          // 30 minutes
            config.setLeakDetectionThreshold(60000); // 60 seconds

            // Performance tuning
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            dataSource = new HikariDataSource(config);

            System.out.println("✅ Database Connection Pool Initialized");

        } catch (Exception e) {
            System.err.println("⚠️ Failed to initialize database pool: " + e.getMessage());
            System.err.println("⚠️ Database tests will be skipped");
        }
    }

    /**
     * Configure RestAssured global settings
     */
    private void configureRestAssured() {
        RestAssuredConfig config = RestAssured.config()
                .httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 5000)
                        .setParam("http.socket.timeout", 30000)
                        .setParam("http.connection-manager.max-total", 200)
                        .setParam("http.connection-manager.max-per-route", 20)
                );

        RestAssured.config = config;
    }

    /**
     * Suite-level teardown - runs once after all tests
     */
    @AfterSuite(alwaysRun = true)
    public void teardownSuite() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("CLEANUP & FINAL METRICS");
        System.out.println("=".repeat(70));

        // Close database connection pool
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("✅ Database Connection Pool Closed");
        }

        // Print final metrics
        printFinalMetrics();

        System.out.println("=".repeat(70));
    }

    // ==========================================
    // TEST-LEVEL SETUP & TEARDOWN
    // ==========================================

    /**
     * Before each test method
     * Initializes thread-local context and entity tracking
     */
    @BeforeMethod(alwaysRun = true)
    public void setupTest() {
        // Initialize thread-local context
        TestContext ctx = testContext.get();
        ctx.reset();
        ctx.threadId = Thread.currentThread().getId();
        ctx.testStartTime = System.currentTimeMillis();

        // Initialize entity tracking for this thread
        long threadId = Thread.currentThread().getId();
        createdEntities.putIfAbsent(threadId, new ConcurrentHashMap<>());

        // Increment total test counter
        totalTests.incrementAndGet();
    }

    /**
     * After each test method
     * Handles cleanup and metrics collection
     */
    @AfterMethod(alwaysRun = true)
    public void teardownTest(ITestResult result) {
        // Update test result metrics
        switch (result.getStatus()) {
            case ITestResult.SUCCESS:
                passedTests.incrementAndGet();
                break;
            case ITestResult.FAILURE:
                failedTests.incrementAndGet();
                logTestFailure(result);
                break;
            case ITestResult.SKIP:
                skippedTests.incrementAndGet();
                break;
        }

        // Cleanup entities created by this thread
        cleanupThreadEntities();

        // Remove thread-local instances to prevent memory leaks
        requestSpec.remove();
        testContext.remove();
    }

    // ==========================================
    // PUBLIC API - Use These in Your Tests
    // ==========================================

    /**
     * Get thread-local RestAssured RequestSpecification
     * Use this in your tests for API calls
     *
     * Example:
     * getRequestSpec()
     *     .body(json)
     *     .post("/api/orders")
     *     .then()
     *     .statusCode(201);
     */
    protected RequestSpecification getRequestSpec() {
        return requestSpec.get();
    }

    /**
     * Get database connection from pool
     * Always use try-with-resources to ensure connection is returned to pool
     *
     * Example:
     * try (Connection conn = getConnection()) {
     *     PreparedStatement ps = conn.prepareStatement("SELECT * FROM orders");
     *     ResultSet rs = ps.executeQuery();
     * }
     */
    protected Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("Database connection pool not initialized");
        }
        return dataSource.getConnection();
    }

    /**
     * Get thread-local test context
     * Use this to store and retrieve test-specific data
     *
     * Example:
     * TestContext ctx = getContext();
     * ctx.userId = "user123";
     * ctx.orderId = "ORD-456";
     */
    protected TestContext getContext() {
        return testContext.get();
    }

    /**
     * Register created entity for automatic cleanup
     *
     * @param entityType Type of entity (e.g., "user", "order", "product")
     * @param entityId Unique identifier of the entity
     *
     * Example:
     * String userId = createUser();
     * registerEntity("user", userId);
     *
     * The entity will be automatically deleted in @AfterMethod
     */
    protected void registerEntity(String entityType, String entityId) {
        long threadId = Thread.currentThread().getId();
        ConcurrentHashMap<String, String> entities = createdEntities.get(threadId);

        if (entities != null) {
            String key = entityType + ":" + entityId;
            entities.put(key, entityId);
        }
    }

    /**
     * Get current thread ID
     * Useful for logging and debugging
     */
    protected long getCurrentThreadId() {
        return Thread.currentThread().getId();
    }

    /**
     * Get test execution duration in milliseconds
     */
    protected long getTestDuration() {
        TestContext ctx = getContext();
        return System.currentTimeMillis() - ctx.testStartTime;
    }

    // ==========================================
    // CLEANUP METHODS
    // ==========================================

    /**
     * Cleanup entities created by current thread
     */
    private void cleanupThreadEntities() {
        long threadId = Thread.currentThread().getId();
        ConcurrentHashMap<String, String> entities = createdEntities.remove(threadId);

        if (entities != null && !entities.isEmpty()) {
            System.out.println("[Thread-" + threadId + "] Cleaning up " +
                    entities.size() + " entities");

            entities.forEach((key, value) -> {
                String[] parts = key.split(":", 2);
                if (parts.length == 2) {
                    String entityType = parts[0];
                    String entityId = parts[1];

                    try {
                        deleteEntity(entityType, entityId);
                    } catch (Exception e) {
                        System.err.println("[Thread-" + threadId + "] Failed to cleanup " +
                                entityType + ":" + entityId + " - " + e.getMessage());
                    }
                }
            });
        }
    }

    /**
     * Delete entity based on type
     * Override these methods in subclasses to implement actual deletion
     *
     * @param entityType Type of entity
     * @param entityId Entity identifier
     */
    private void deleteEntity(String entityType, String entityId) {
        switch (entityType.toLowerCase()) {
            case "user":
                deleteUser(entityId);
                break;
            case "product":
                deleteProduct(entityId);
                break;
            case "order":
                deleteOrder(entityId);
                break;
            case "payment":
                deletePayment(entityId);
                break;
            default:
                System.err.println("Unknown entity type: " + entityType);
        }
    }

    // ==========================================
    // ENTITY DELETION - Override in Subclasses
    // ==========================================

    /**
     * Delete user by ID
     * Override this method to implement actual user deletion
     */
    protected void deleteUser(String userId) {
        // Default implementation - override in subclass
        try {
            getRequestSpec()
                    .delete("/api/users/" + userId)
                    .then()
                    .statusCode(204);
        } catch (Exception e) {
            System.err.println("Failed to delete user: " + userId);
        }
    }

    /**
     * Delete product by SKU
     * Override this method to implement actual product deletion
     */
    protected void deleteProduct(String productSku) {
        // Default implementation - override in subclass
        try {
            getRequestSpec()
                    .delete("/api/products/" + productSku)
                    .then()
                    .statusCode(204);
        } catch (Exception e) {
            System.err.println("Failed to delete product: " + productSku);
        }
    }

    /**
     * Delete order by ID
     * Override this method to implement actual order deletion
     */
    protected void deleteOrder(String orderId) {
        // Default implementation - override in subclass
        try {
            getRequestSpec()
                    .delete("/api/orders/" + orderId)
                    .then()
                    .statusCode(204);
        } catch (Exception e) {
            System.err.println("Failed to delete order: " + orderId);
        }
    }

    /**
     * Delete payment by ID
     * Override this method to implement actual payment deletion
     */
    protected void deletePayment(String paymentId) {
        // Default implementation - override in subclass
        try {
            getRequestSpec()
                    .delete("/api/payments/" + paymentId)
                    .then()
                    .statusCode(204);
        } catch (Exception e) {
            System.err.println("Failed to delete payment: " + paymentId);
        }
    }

    // ==========================================
    // METRICS & REPORTING
    // ==========================================

    /**
     * Print final test execution metrics
     */
    private void printFinalMetrics() {
        int total = totalTests.get();
        int passed = passedTests.get();
        int failed = failedTests.get();
        int skipped = skippedTests.get();

        double successRate = total > 0 ? (passed * 100.0) / total : 0.0;

        System.out.println("\nFINAL TEST METRICS:");
        System.out.println("  Total Tests: " + total);
        System.out.println("  Passed: " + passed + " ✅");
        System.out.println("  Failed: " + failed + " ❌");
        System.out.println("  Skipped: " + skipped + " ⏭️");
        System.out.println("  Success Rate: " + String.format("%.2f%%", successRate));

        if (failed > 0) {
            System.out.println("\n⚠️ " + failed + " test(s) failed. Check logs for details.");
        }
    }

    /**
     * Log test failure details
     */
    private void logTestFailure(ITestResult result) {
        System.err.println("\n❌ TEST FAILED: " + result.getName());
        System.err.println("   Thread ID: " + Thread.currentThread().getId());

        if (result.getThrowable() != null) {
            System.err.println("   Error: " + result.getThrowable().getMessage());
        }
    }

    /**
     * Get current success rate
     */
    protected double getSuccessRate() {
        int total = totalTests.get();
        int passed = passedTests.get();
        return total > 0 ? (passed * 100.0) / total : 0.0;
    }

    // ==========================================
    // TEST CONTEXT - Inner Class
    // ==========================================

    /**
     * Thread-local test context
     * Stores data specific to current test execution
     *
     * Add more fields as needed for your tests
     */
    public static class TestContext {
        // Test metadata
        public long threadId;
        public long testStartTime;

        // Business entities - commonly used
        public String userId;
        public String userName;
        public String email;

        public String productSku;
        public String productName;

        public String orderId;
        public String orderStatus;

        public String paymentId;
        public String transactionId;

        // Session/auth
        public String sessionToken;
        public String authToken;

        /**
         * Reset all context data
         * Called before each test
         */
        public void reset() {
            userId = null;
            userName = null;
            email = null;
            productSku = null;
            productName = null;
            orderId = null;
            orderStatus = null;
            paymentId = null;
            transactionId = null;
            sessionToken = null;
            authToken = null;
        }

        /**
         * Print current context state (useful for debugging)
         */
        public void printState() {
            System.out.println("TestContext[Thread-" + threadId + "]:");
            System.out.println("  userId: " + userId);
            System.out.println("  orderId: " + orderId);
            System.out.println("  productSku: " + productSku);
            System.out.println("  Duration: " +
                    (System.currentTimeMillis() - testStartTime) + "ms");
        }
    }
}