// CleanupManager.java
package com.amazon.tests.dataseeding.cleanup;

import com.amazon.tests.dataseeding.core.SeedingContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Centralized cleanup manager
 * Executes all registered cleanup tasks
 */
@Slf4j
public class CleanupManager {

    private final SeedingContext context;

    public CleanupManager(SeedingContext context) {
        this.context = context;
    }

    /**
     * Execute all cleanup tasks in reverse order (LIFO)
     */
    public CleanupResult executeCleanup() {
        log.info("Starting cleanup for namespace: {} ({} tasks)",
                context.getNamespace(),
                context.getCleanupTasks().size());

        long startTime = System.currentTimeMillis();
        int successCount = 0;
        int failureCount = 0;

        while (!context.getCleanupTasks().isEmpty()) {
            SeedingContext.CleanupTask task = context.getCleanupTasks().pop();

            try {
                task.getAction().run();
                successCount++;
                log.debug("Cleanup succeeded: {}", task.getDescription());
            } catch (Exception e) {
                failureCount++;
                log.error("Cleanup failed: {}", task.getDescription(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        log.info("Cleanup completed in {}ms. Success: {}, Failures: {}",
                duration, successCount, failureCount);

        // Print stats
        log.info("Seeding statistics: {}", context.getStats());

        return CleanupResult.builder()
                .namespace(context.getNamespace())
                .successCount(successCount)
                .failureCount(failureCount)
                .durationMs(duration)
                .build();
    }

    @lombok.Data
    @lombok.Builder
    public static class CleanupResult {
        private String namespace;
        private int successCount;
        private int failureCount;
        private long durationMs;
    }
}