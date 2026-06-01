package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;


import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONE instance shared across ALL suites and modules.
 *
 * Key behaviors:
 * 1. Created lazily on first suite registration
 * 2. Resizes when new suites register (if needed)
 * 3. NOT destroyed until ALL suites complete
 * 4. Thread-safe throughout
 */
public class SharedPoolManager {

    // ── Singleton ────────────────────────────────────────────────────
    private static volatile SharedPoolManager instance;
    private static final Object LOCK = new Object();

    public static SharedPoolManager getInstance() {
        if (instance == null) {
            synchronized (LOCK) {
                if (instance == null) {
                    instance = new SharedPoolManager();
                }
            }
        }
        return instance;
    }

    // ── State ────────────────────────────────────────────────────────
    private final ConcurrentHashMap<String, MultiSuitePoolCalculator.SuiteRegistration> suites
            = new ConcurrentHashMap<>();

    private final AtomicInteger activeSuiteCount = new AtomicInteger(0);
    private final AtomicBoolean initialized      = new AtomicBoolean(false);

    // ── DB Pools ─────────────────────────────────────────────────────
    private volatile HikariDataSource usersPool;
    private volatile HikariDataSource productsPool;
    private volatile HikariDataSource ordersPool;
    private volatile HikariDataSource paymentsPool;

    // ── HTTP Pool ────────────────────────────────────────────────────
    private volatile PoolingHttpClientConnectionManager httpManager;
    private volatile CloseableHttpClient               httpClient;

    // ── ThreadLocal spec — one per thread across all suites ──────────
    private static final ThreadLocal<RequestSpecification> threadSpec
            = new ThreadLocal<>();

    private SharedPoolManager() {}

    // ── Suite lifecycle ──────────────────────────────────────────────

    /**
     * Called by each module's BaseTest @BeforeSuite.
     * Initializes or expands pools based on new total thread count.
     */
    public synchronized void onSuiteStart(
            String suiteName,
            int threadCount,
            MultiSuitePoolCalculator.ModuleProfile profile,
            TestEnvironmentConfig env) {

        suites.put(suiteName,
                new MultiSuitePoolCalculator.SuiteRegistration(
                        suiteName, threadCount, profile));

        int totalThreads = suites.values().stream()
                .mapToInt(s -> s.threadCount())
                .sum();

        MultiSuitePoolCalculator calc =
                new MultiSuitePoolCalculator(totalThreads);

        if (!initialized.get()) {
            // First suite — create pools
            createPools(calc, env);
            initialized.set(true);
            System.out.println("✅ Pools CREATED for suite: " + suiteName);
        } else {
            // Subsequent suite — check if we need to expand
            expandIfNeeded(calc, env);
            System.out.println("✅ Pools VERIFIED for suite: " + suiteName);
        }

        activeSuiteCount.incrementAndGet();
        calc.printSummary("Suite start: " + suiteName);
    }

    /**
     * Called by each module's BaseTest @AfterSuite.
     * Only destroys pools when ALL suites complete.
     */
    public synchronized void onSuiteFinish(String suiteName) {
        suites.remove(suiteName);
        int remaining = activeSuiteCount.decrementAndGet();

        System.out.printf("Suite finished: %s | %d suites still running%n",
                suiteName, remaining);

        if (remaining == 0) {
            printFinalStats();
            destroyPools();
            initialized.set(false);
            System.out.println("✅ All suites complete — pools destroyed");
        }
    }

    // ── Pool creation ────────────────────────────────────────────────

    private void createPools(MultiSuitePoolCalculator calc,
                             TestEnvironmentConfig env) {
        usersPool    = buildHikariPool("users_db",
                env.getUsersDbUrl(),    calc, env);
        productsPool = buildHikariPool("products_db",
                env.getProductsDbUrl(), calc, env);
        ordersPool   = buildHikariPool("orders_db",
                env.getOrdersDbUrl(),   calc, env);
        paymentsPool = buildHikariPool("payments_db",
                env.getPaymentsDbUrl(), calc, env);

        buildHttpPool(calc);
    }

    /**
     * Expand pools if new suite increases total thread count beyond
     * current capacity. Never shrinks during a run — only grows.
     */
    private void expandIfNeeded(MultiSuitePoolCalculator calc,
                                TestEnvironmentConfig env) {
        int needed = calc.httpMaxPerRoute();
        int current = httpManager != null
                ? httpManager.getDefaultMaxPerRoute() : 0;

        if (needed > current) {
            System.out.printf(
                    "  Expanding HTTP pool: %d → %d (new suite added threads)%n",
                    current, needed);
            // Rebuild HTTP pool with new size
            buildHttpPool(calc);
        }

        // DB pools — HikariCP supports runtime resize
        expandHikariIfNeeded("users_db",    usersPool,    calc, env);
        expandHikariIfNeeded("products_db", productsPool, calc, env);
        expandHikariIfNeeded("orders_db",   ordersPool,   calc, env);
        expandHikariIfNeeded("payments_db", paymentsPool, calc, env);
    }

    private void expandHikariIfNeeded(String dbName,
                                      HikariDataSource pool,
                                      MultiSuitePoolCalculator calc,
                                      TestEnvironmentConfig env) {
        int needed  = calc.dbPoolSize(dbName, suites);
        int current = pool.getMaximumPoolSize();

        if (needed > current) {
            System.out.printf(
                    "  Expanding %s pool: %d → %d%n",
                    dbName, current, needed);
            // HikariCP supports live resize
            pool.setMaximumPoolSize(needed);
            pool.setMinimumIdle(Math.max(1, needed / 2));
        }
    }

    private HikariDataSource buildHikariPool(
            String dbName,
            String jdbcUrl,
            MultiSuitePoolCalculator calc,
            TestEnvironmentConfig env) {

        int poolSize = calc.dbPoolSize(dbName, suites);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(env.getDbUsername());
        config.setPassword(env.getDbPassword());
        config.setPoolName("HikariPool-" + dbName);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(Math.max(1, poolSize / 2));
        config.setConnectionTimeout(calc.dbConnectionTimeoutMs());
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);
        config.setKeepaliveTime(120_000);
        config.setConnectionTestQuery("SELECT 1");
        config.setLeakDetectionThreshold(
                calc.dbConnectionTimeoutMs() * 2);

        System.out.printf("  DB pool %-15s: max=%-3d idle=%-3d%n",
                dbName, poolSize, Math.max(1, poolSize / 2));

        return new HikariDataSource(config);
    }

    private void buildHttpPool(MultiSuitePoolCalculator calc) {
        // Close old if exists
        if (httpManager != null) {
            try { httpClient.close(); } catch (Exception ignored) {}
            httpManager.close();
        }

        httpManager = new PoolingHttpClientConnectionManager();
        httpManager.setMaxTotal(calc.httpMaxTotal());
        httpManager.setDefaultMaxPerRoute(calc.httpMaxPerRoute());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(
                        calc.httpSocketTimeoutMs())
                .setConnectTimeout(5_000)
                .setSocketTimeout(calc.httpSocketTimeoutMs())
                .build();

        httpClient = HttpClients.custom()
                .setConnectionManager(httpManager)
                .setDefaultRequestConfig(requestConfig)
                .setKeepAliveStrategy(
                        (response, context) -> 30_000)
                .build();

        System.out.printf("  HTTP pool: maxPerRoute=%-3d maxTotal=%-3d%n",
                calc.httpMaxPerRoute(), calc.httpMaxTotal());
    }

    // ── Access for tests ─────────────────────────────────────────────

    public RequestSpecification getSpec(String baseUrl) {
        if (threadSpec.get() == null) {
            threadSpec.set(
                    new RequestSpecBuilder()
                            .setBaseUri(baseUrl)
                            .setConfig(RestAssuredConfig.config()
                                    .httpClient(HttpClientConfig
                                            .httpClientConfig()
                                            .httpClientFactory(() -> httpClient)
                                            .reuseHttpClientInstance()))
                            .build()
            );
        }
        return threadSpec.get();
    }

    public HikariDataSource getUsersPool()    { return usersPool; }
    public HikariDataSource getProductsPool() { return productsPool; }
    public HikariDataSource getOrdersPool()   { return ordersPool; }
    public HikariDataSource getPaymentsPool() { return paymentsPool; }

    // ── Monitoring ───────────────────────────────────────────────────

    public void printFinalStats() {
        System.out.println("\n=== Final Pool Stats ===");

        printHikariStats("users_db",    usersPool);
        printHikariStats("products_db", productsPool);
        printHikariStats("orders_db",   ordersPool);
        printHikariStats("payments_db", paymentsPool);

        if (httpManager != null) {
            var stats = httpManager.getTotalStats();
            System.out.printf("""
                HTTP Pool:
                  Available : %d
                  Leased    : %d
                  Pending   : %d ← must be 0
                  Max       : %d%n""",
                    stats.getAvailable(), stats.getLeased(),
                    stats.getPending(),   stats.getMax());
        }
    }

    private void printHikariStats(String name, HikariDataSource pool) {
        if (pool == null) return;
        var mx = pool.getHikariPoolMXBean();
        System.out.printf(
                "DB %-15s: active=%-3d idle=%-3d waiting=%-3d total=%-3d%n",
                name,
                mx.getActiveConnections(),
                mx.getIdleConnections(),
                mx.getThreadsAwaitingConnection(),
                mx.getTotalConnections()
        );
    }

    private void destroyPools() {
        if (usersPool    != null) usersPool.close();
        if (productsPool != null) productsPool.close();
        if (ordersPool   != null) ordersPool.close();
        if (paymentsPool != null) paymentsPool.close();
        try {
            if (httpClient  != null) httpClient.close();
            if (httpManager != null) httpManager.close();
        } catch (Exception ignored) {}
    }
}
