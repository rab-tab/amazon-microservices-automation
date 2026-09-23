package com.amazon.tests;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.auth.NoAuthStrategy;
import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.config.restAsssured.RestClient;
import com.amazon.tests.dataseeding.cleanup.CleanupManager;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.reports.ExtentReportManager;
import com.amazon.tests.reports.ReportingFilter;
import com.amazon.tests.reports.TestReporter;
import com.amazon.tests.reports.TestReporterFactory;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.transport.RestHttpClient;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.metrics.MetricsHttpServer;
import com.amazon.tests.utils.metrics.MetricsSupport;
import com.amazon.tests.utils.retry.RetryHandler;
import com.amazon.tests.utils.validators.DatabaseValidator;
import com.aventstack.extentreports.ExtentTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.aeonbits.owner.ConfigFactory;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.MDC;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.BeforeSuite;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Base class for all tests.
 * Owns: suite-wide transport wiring, per-method seeding/cleanup lifecycle,
 * pre-suite dependency health checks, and small logging/wait helpers.
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

    // Health check tuning — short and deliberate: a genuinely-down
    // dependency should fail in a few seconds, not hang for whatever a
    // client library's own default timeout happens to be (this is exactly
    // what produced the confusing multi-second Kafka TimeoutException
    // mid-test that this check exists to prevent).
    private static final Duration HEALTH_CHECK_TIMEOUT = Duration.ofSeconds(3);

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

        // Eagerly initializes HikariCP pools for all 4 DBs — if Postgres is
        // down, this already throws here. No separate DB health check needed
        // below; this is the existing coverage for that dependency.
        DatabaseValidator.getInstance();
        // ExtentReportManager.getInstance();

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

        // ⭐ NEW — fail the whole suite fast, with one clear message listing
        // everything that's down, instead of letting the first
        // Kafka/Redis/service-dependent test fail deep inside a confusing
        // low-level exception (e.g. org.apache.kafka.common.errors.
        // TimeoutException with no indication Kafka just isn't running).
        // Runs once per suite (@BeforeSuite), not per class/method — repeating
        // this before every test would add latency for no benefit once
        // connectivity is already confirmed.
        performHealthChecks();

        log.info("Test suite setup complete");
    }

    /**
     * Checks every external dependency this suite touches, collects ALL
     * failures (not just the first one hit) so a multi-dependency outage is
     * visible in one shot, and fails fast with a single clear message if
     * anything is down.
     */
    private void performHealthChecks() {
        log.info("Running pre-suite health checks...");

        List<String> failures = new ArrayList<>();

        checkHttpHealth("api-gateway", ConfigManager.getInstance().getBaseUrl(), failures);
        checkHttpHealth("user-service", ConfigManager.getInstance().getUserServiceUrl(), failures);
        checkHttpHealth("product-service", ConfigManager.getInstance().getProductServiceUrl(), failures);
        checkHttpHealth("order-service", ConfigManager.getInstance().getOrderServiceUrl(), failures);
        // TODO: confirm whether ConfigManager exposes a payment-service URL —
        // it's never printed in the "Environment: ..." log line above, so its
        // existence here is unconfirmed. Wire in once confirmed:
        // checkHttpHealth("payment-service", ConfigManager.getInstance().getPaymentServiceUrl(), failures);

        checkRedisHealth(failures);
        checkKafkaHealth(failures);

        if (!failures.isEmpty()) {
            String message = "Pre-suite health check FAILED — one or more dependencies are unreachable:\n  - "
                    + String.join("\n  - ", failures);
            log.error(message);
            throw new IllegalStateException(message);
        }

        log.info("✅ All dependencies healthy — proceeding with suite");
    }

    private void checkHttpHealth(String serviceName, String baseUrl, List<String> failures) {
        String healthUrl = baseUrl + "/actuator/health";
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(HEALTH_CHECK_TIMEOUT)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(healthUrl))
                    .timeout(HEALTH_CHECK_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                failures.add(serviceName + " (" + healthUrl + ") returned HTTP " + response.statusCode());
            } else {
                log.info("  ✓ {} healthy", serviceName);
            }
        } catch (Exception e) {
            failures.add(serviceName + " (" + healthUrl + ") unreachable: " + e.getMessage());
        }
    }

    private void checkRedisHealth(List<String> failures) {
        if (RedisValidator.isRedisUp()) {
            log.info("  ✓ Redis healthy");
        } else {
            failures.add("Redis unreachable");
        }
    }

    private void checkKafkaHealth(List<String> failures) {
        // TODO: confirm the right source for bootstrap-servers — every
        // service's own config in this project hardcodes localhost:9092, so
        // that's used as the default here too, overridable via
        // -Dkafka.bootstrap.servers. If TestConfig/ConfigManager already
        // exposes this as a first-class property, prefer that instead.
        String bootstrapServers = System.getProperty("kafka.bootstrap.servers", "localhost:9092");

        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) HEALTH_CHECK_TIMEOUT.toMillis());
        // Bounds the AdminClient's own internal retry window for the whole
        // describeCluster() call, not just a single request attempt — belt
        // and suspenders alongside the explicit Future.get() timeout below.
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) HEALTH_CHECK_TIMEOUT.toMillis());

        // ⭐ NOT try-with-resources — that calls AdminClient's no-arg close(),
        // which has no bound and blocks waiting for the internal network
        // thread to wind down cleanly. Against an unreachable broker that
        // thread can keep retrying indefinitely, so the implicit close()
        // hangs even after the timeout below has already fired and been
        // caught — this was the actual cause of the reported infinite hang,
        // not the describeCluster() call itself. Explicit close(Duration)
        // forces the client to give up and release its thread within a
        // bounded time regardless of what the broker is doing.
        AdminClient adminClient = AdminClient.create(props);
        try {
            org.apache.kafka.common.KafkaFuture<?> future = adminClient.describeCluster().nodes();

            // Manual poll loop instead of future.get(timeout, TimeUnit) —
            // TimeUnit doesn't resolve in this project's environment (same
            // constraint hit earlier fixing toxiproxy-server's shutdown).
            // Plain millis + Thread.sleep(long) avoids it entirely.
            long deadline = System.currentTimeMillis() + HEALTH_CHECK_TIMEOUT.toMillis();
            while (!future.isDone() && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            if (!future.isDone()) {
                failures.add("Kafka (" + bootstrapServers + ") unreachable: did not respond within "
                        + HEALTH_CHECK_TIMEOUT.toMillis() + "ms");
            } else {
                future.get(); // already done — returns/throws immediately, no blocking
                log.info("  ✓ Kafka healthy ({})", bootstrapServers);
            }
        } catch (Exception e) {
            failures.add("Kafka (" + bootstrapServers + ") unreachable: " + e.getMessage());
        } finally {
            adminClient.close(Duration.ofSeconds(2));
        }
    }

    // ==========================================
    // METHOD SETUP / TEARDOWN (seeding lifecycle)
    // ==========================================

    @BeforeMethod(alwaysRun = true)
    public void setupTestMethod(Method method) {
        String namespace = generateNamespace();

        if (ConfigManager.getInstance().isReporterEnabled("extent")) {
            ExtentReportManager.getInstance().createTest(
                    getClass().getSimpleName() + "." + method.getName()
            );
        }

        context = new SeedingContext(namespace, testConfig, executor);
        cleanupManager = new CleanupManager(context);
        testStart = System.currentTimeMillis();

        log.info("Test started: {} | namespace: {}", this.getClass().getSimpleName(), namespace);
    }

    @AfterMethod(alwaysRun = true)
    public void cleanupTestMethod() {
        MetricsSupport.recordTestDuration(System.currentTimeMillis() - testStart);
        MetricsSupport.pushToPrometheus("automation-suite");

        if (ConfigManager.getInstance().isReporterEnabled("extent")) {
            ExtentReportManager.getInstance().removeTest();   // also redundant — listener already does this, see note below
        }

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

        if (ConfigManager.getInstance().isReporterEnabled("extent")) {
            ExtentReportManager.getInstance().flush();
        }

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
        logStep(step, (Object[]) null);
    }

    protected void logStep(String message, Object... args) {
        String formatted = (args == null || args.length == 0) ? message : formatMessage(message, args);
        log.info("STEP: {}", formatted);
        reporter.logStep(formatted);
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

    // BaseTest.java
    protected Runnable withTestContext(Runnable task) {
        return withTestContext(task, null);
    }

    protected Runnable withTestContext(Runnable task, List<Response> sharedResponseGroup) {
        ExtentTest currentTest = ExtentReportManager.getInstance().getTest();
        Map<String, String> mdcContext = MDC.getCopyOfContextMap();
        return () -> {
            if (mdcContext != null) MDC.setContextMap(mdcContext);
            ExtentReportManager.getInstance().attachTest(currentTest);
            if (sharedResponseGroup != null) {
                ReportingFilter.attachCaptureGroup(sharedResponseGroup);   // NEW
            }
            try {
                task.run();
            } finally {
                MDC.clear();
                ExtentReportManager.getInstance().removeTest();
                ReportingFilter.clearCaptureGroup();   // NEW
            }
        };
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