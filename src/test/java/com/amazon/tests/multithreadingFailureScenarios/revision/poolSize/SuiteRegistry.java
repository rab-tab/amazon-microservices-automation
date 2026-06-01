package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;



import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Tracks all active suites and their thread counts.
 *
 * When SDET runs one module  → registry has 1 entry
 * When CI runs all modules   → registry has N entries
 * Pool is sized for sum of all registered thread counts.
 */
public class SuiteRegistry {

    // Suite name → thread count for that suite
    private static final ConcurrentHashMap<String, Integer> activeSuites
            = new ConcurrentHashMap<>();

    // Total concurrent threads across ALL suites
    private static final AtomicInteger totalThreads
            = new AtomicInteger(0);

    // How many suites have started (for logging)
    private static final AtomicInteger suiteStartCount
            = new AtomicInteger(0);

    // How many suites have finished (triggers pool right-sizing)
    private static final AtomicInteger suiteFinishCount
            = new AtomicInteger(0);

    /**
     * Called by each suite's @BeforeSuite.
     * Returns the TOTAL thread count across all currently known suites.
     */
    public static synchronized int registerSuite(String suiteName,
                                                 int threadCount) {
        activeSuites.put(suiteName, threadCount);
        totalThreads.set(
                activeSuites.values().stream()
                        .mapToInt(Integer::intValue)
                        .sum()
        );
        suiteStartCount.incrementAndGet();

        System.out.printf("""
            ┌─────────────────────────────────────────────┐
            │  Suite Registered: %-24s│
            │  This suite threads : %-22d│
            │  Total suites active: %-22d│
            │  Total threads now  : %-22d│
            └─────────────────────────────────────────────┘
            %n""",
                suiteName, threadCount,
                activeSuites.size(),
                totalThreads.get()
        );

        return totalThreads.get();
    }

    /**
     * Called by each suite's @AfterSuite.
     */
    public static synchronized void deregisterSuite(String suiteName) {
        Integer removed = activeSuites.remove(suiteName);
        if (removed != null) {
            totalThreads.set(
                    activeSuites.values().stream()
                            .mapToInt(Integer::intValue)
                            .sum()
            );
        }
        suiteFinishCount.incrementAndGet();

        System.out.printf("Suite finished: %s | Remaining active suites: %d%n",
                suiteName, activeSuites.size());
    }

    public static int getTotalThreads()   { return totalThreads.get(); }
    public static int getActiveSuiteCount() { return activeSuites.size(); }

    public static void printAllSuites() {
        System.out.println("\nActive Suites:");
        activeSuites.forEach((name, threads) ->
                System.out.printf("  %-30s : %d threads%n", name, threads));
        System.out.println("  Total concurrent threads: " + totalThreads.get());
    }
}
