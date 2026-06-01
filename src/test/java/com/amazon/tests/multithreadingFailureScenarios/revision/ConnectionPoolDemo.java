package com.amazon.tests.multithreadingFailureScenarios.revision;

import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class ConnectionPoolDemo {

    // ================================================================
    // SCENARIO 1: Pool too small — starvation
    // ================================================================
    static HikariDataSource createPool(String name, int maxSize,
                                       int timeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setPoolName(name);
        config.setMaximumPoolSize(maxSize);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(timeout);
        config.setLeakDetectionThreshold(2000); // warn if held > 2s
        return new HikariDataSource(config);
    }

    static void scenario1_PoolStarvation() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 1: Pool too small — starvation");
        System.out.println("  Threads=10, PoolSize=2, Timeout=1000ms");
        System.out.println("=".repeat(60));

        HikariDataSource pool = createPool("TooSmall", 2, 1000);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger timeout = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);

        for (int i = 1; i <= 10; i++) {
            final int testNum = i;
            executor.submit(() -> {
                try (Connection conn = pool.getConnection()) {
                    // Simulate DB validation query
                    PreparedStatement ps = conn.prepareStatement("SELECT 1");
                    ps.execute();
                    Thread.sleep(200); // simulate test holding connection
                    success.incrementAndGet();
                    System.out.println("  ✅ Test-" + testNum + " got connection");
                } catch (SQLTransientConnectionException e) {
                    timeout.incrementAndGet();
                    System.out.println("  ❌ Test-" + testNum
                            + " TIMEOUT: pool exhausted");
                } catch (Exception e) {
                    System.out.println("  ❌ Test-" + testNum + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();
        pool.close();
        System.out.println("Result → Success: " + success.get()
                + " | Timeout: " + timeout.get());
    }

    // ================================================================
    // SCENARIO 2: Pool too large — resource waste + DB overload
    // ================================================================
    static void scenario2_PoolOversized() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 2: Pool too large — DB overload");
        System.out.println("  PoolSize=100, but PostgreSQL max_connections=100");
        System.out.println("  Other apps also connect → DB runs out");
        System.out.println("=".repeat(60));

        // Simulated — showing the math
        int dbMaxConnections = 100;
        int otherAppsConnections = 40;  // user-service, order-service etc.
        int yourPoolSize = 100;         // what you configured

        int available = dbMaxConnections - otherAppsConnections;
        boolean willFail = yourPoolSize > available;

        System.out.println("  DB max_connections     : " + dbMaxConnections);
        System.out.println("  Other apps using       : " + otherAppsConnections);
        System.out.println("  Available for tests    : " + available);
        System.out.println("  Your pool size         : " + yourPoolSize);
        System.out.println("  Result                 : "
                + (willFail ? "❌ DB will reject connections" : "✅ OK"));
        System.out.println("  Fix: yourPoolSize ≤ " + (available - 10)
                + " (leave 10 buffer)");
    }

    // ================================================================
    // SCENARIO 3: Connection leak — pool drains silently
    // ================================================================
    static void scenario3_ConnectionLeak() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 3: Connection leak — pool drains");
        System.out.println("  PoolSize=3, but connections never returned");
        System.out.println("=".repeat(60));

        HikariDataSource pool = createPool("LeakPool", 3, 1000);
        AtomicInteger leaked = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        // BAD: no try-with-resources → connection never returned to pool
        for (int i = 1; i <= 5; i++) {
            final int num = i;
            new Thread(() -> {
                try {
                    Connection conn = pool.getConnection(); // ❌ never closed
                    leaked.incrementAndGet();
                    System.out.println("  ⚠️  Test-" + num
                            + " leaked connection (never returned)");
                    // conn.close() missing → connection stays checked out
                } catch (SQLException e) {
                    failed.incrementAndGet();
                    System.out.println("  ❌ Test-" + num
                            + " failed: pool empty from leaks");
                }
            }).start();
            Thread.sleep(50);
        }

        Thread.sleep(2000); // wait for leak detection
        System.out.println("  Leaked: " + leaked.get()
                + " | Failed to get connection: " + failed.get());
        System.out.println("  HikariCP leakDetectionThreshold logged warnings above");
        pool.close();
    }

    // ================================================================
    // SCENARIO 4: Correct sizing — the formula in action
    // ================================================================
    static void scenario4_CorrectSizing() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("SCENARIO 4: Correct sizing — threads == pool size");
        System.out.println("  Threads=5, PoolSize=5, Timeout=5000ms");
        System.out.println("=".repeat(60));

        HikariDataSource pool = createPool("CorrectPool", 5, 5000);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(10); // 10 tests, 5 threads

        for (int i = 1; i <= 10; i++) {
            final int testNum = i;
            executor.submit(() -> {
                try (Connection conn = pool.getConnection()) { // ✅ auto-close
                    PreparedStatement ps = conn.prepareStatement("SELECT 1");
                    ps.execute();
                    Thread.sleep(100);
                    success.incrementAndGet();
                    System.out.println("  ✅ Test-" + testNum + " completed");
                } catch (Exception e) {
                    failed.incrementAndGet();
                    System.out.println("  ❌ Test-" + testNum + ": " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        HikariPoolMXBean stats = pool.getHikariPoolMXBean();
        System.out.println("\n  Pool stats at end:");
        System.out.println("  Active: " + stats.getActiveConnections());
        System.out.println("  Idle  : " + stats.getIdleConnections());
        System.out.println("  Total : " + stats.getTotalConnections());
        System.out.println("  Waiting threads: " + stats.getThreadsAwaitingConnection());
        System.out.println("\n  Result → Success: " + success.get()
                + " | Failed: " + failed.get());
        pool.close();
    }
}   
