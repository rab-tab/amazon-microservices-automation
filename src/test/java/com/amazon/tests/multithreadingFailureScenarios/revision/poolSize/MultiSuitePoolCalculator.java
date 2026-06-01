package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Updated calculator — works from TOTAL threads across all suites,
 * not just one suite's thread-count.
 *
 * Also accounts for module-specific DB usage patterns.
 * Orders module hits orders_db heavily.
 * Payments module hits payments_db heavily.
 * Each module has a different connection profile.
 */
public class MultiSuitePoolCalculator {

    // ── Module connection profiles ───────────────────────────────────
    // Different modules use different DBs with different intensity
    public enum ModuleProfile {
        //                        usersDb  productsDb  ordersDb  paymentsDb
        ORDERS   (               2,       1,          3,        1         ),
        PAYMENTS (               1,       0,          1,        3         ),
        USERS    (               3,       0,          0,        0         ),
        PRODUCTS (               0,       3,          0,        0         ),
        FULL     (               2,       2,          2,        2         ); // CI

        final int usersDbConns;
        final int productsDbConns;
        final int ordersDbConns;
        final int paymentsDbConns;

        ModuleProfile(int u, int p, int o, int pay) {
            this.usersDbConns    = u;
            this.productsDbConns = p;
            this.ordersDbConns   = o;
            this.paymentsDbConns = pay;
        }
    }

    private final int    totalThreads;   // sum across all suites
    private final double safetyBuffer;
    private final int    dbPoolCount;
    private final int    httpHostCount;

    public MultiSuitePoolCalculator(int totalThreads) {
        this(totalThreads, 1.5, 4, 1);
    }

    public MultiSuitePoolCalculator(int totalThreads,
                                    double safetyBuffer,
                                    int dbPoolCount,
                                    int httpHostCount) {
        this.totalThreads  = totalThreads;
        this.safetyBuffer  = safetyBuffer;
        this.dbPoolCount   = dbPoolCount;
        this.httpHostCount = httpHostCount;
    }

    // ── Per-database pool sizes ──────────────────────────────────────

    /**
     * Pool size for a specific DB, considering which modules use it.
     *
     * Formula:
     *   Each suite contributes: suiteThreads × connectionsPerThread
     *   Sum across all suites × safetyBuffer = pool size for that DB
     */
    public int dbPoolSize(String dbName,
                          ConcurrentHashMap<String, SuiteRegistration> suites) {
        int totalRequired = suites.values().stream()
                .mapToInt(suite -> {
                    int connsPerThread = getConnectionsPerThread(
                            dbName, suite.profile());
                    return suite.threadCount() * connsPerThread;
                })
                .sum();

        int withBuffer = (int) Math.ceil(totalRequired * safetyBuffer);
        return Math.max(withBuffer, 2); // minimum 2
    }

    private int getConnectionsPerThread(String dbName,
                                        ModuleProfile profile) {
        return switch (dbName) {
            case "users_db"    -> profile.usersDbConns;
            case "products_db" -> profile.productsDbConns;
            case "orders_db"   -> profile.ordersDbConns;
            case "payments_db" -> profile.paymentsDbConns;
            default            -> 1;
        };
    }

    // ── HTTP pool sizes ──────────────────────────────────────────────

    public int httpMaxPerRoute() {
        // Peak simultaneous calls = totalThreads × 2 (BeforeMethod parallel seeds)
        return Math.max(
                (int) Math.ceil(totalThreads * 2 * safetyBuffer),
                5 // minimum
        );
    }

    public int httpMaxTotal() {
        return httpMaxPerRoute() * httpHostCount;
    }

    // ── Timeouts scale with load ─────────────────────────────────────

    public long dbConnectionTimeoutMs() {
        // More total threads = more contention = longer acceptable wait
        return Math.min(5000L + (totalThreads * 500L), 30000L);
    }

    public int httpSocketTimeoutMs() {
        return (int) Math.min(10000L + (totalThreads * 500L), 60000L);
    }

    public void printSummary(String trigger) {
        System.out.printf("""
            ╔══════════════════════════════════════════════════════════╗
            ║  Pool Recalculation Triggered by: %-22s║
            ╠══════════════════════════════════════════════════════════╣
            ║  Total threads across all suites : %-22d║
            ╠══════════════════════════════════════════════════════════╣
            ║  HTTP maxPerRoute : %-36d║
            ║  HTTP maxTotal    : %-36d║
            ║  HTTP socketTimeout: %-34dms║
            ║  DB connTimeout   : %-34dms║
            ╚══════════════════════════════════════════════════════════╝
            %n""",
                trigger, totalThreads,
                httpMaxPerRoute(), httpMaxTotal(),
                httpSocketTimeoutMs(), dbConnectionTimeoutMs()
        );
    }


}
