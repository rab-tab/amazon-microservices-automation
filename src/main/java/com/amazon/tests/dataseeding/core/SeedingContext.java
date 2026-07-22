package com.amazon.tests.dataseeding.core;


import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.config.restAsssured.RestClient;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.transport.RequestExecutor;
import lombok.Getter;

import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Shared context for all seeders
 * Provides access to common resources and tracks cleanup
 */
@Getter
public class SeedingContext {

    private final String namespace;
    private final RestClient restClient;
    private final RestAssuredConfig restAssuredConfig;
    private final TestConfig config;

    // Cleanup tasks in LIFO order
    private final Deque<CleanupTask> cleanupTasks;

    // Shared cache for cross-seeder data
    private final Map<String, Object> cache;

    // Track seeding statistics
    private final Map<String, Integer> seedingStats;
    private final RequestExecutor executor;

    public SeedingContext(String namespace, TestConfig config, RequestExecutor executor) {
        this.namespace = namespace;
        this.config=config;
        this.executor = executor;
        this.restClient = new RestClient();
        this.restAssuredConfig = new RestAssuredConfig(config);
        this.cleanupTasks = new ConcurrentLinkedDeque<>();
        this.cache = new ConcurrentHashMap<>();
        this.seedingStats = new ConcurrentHashMap<>();
    }

    /**
     * Register cleanup task
     */
    public void registerCleanup(String description, Runnable action) {
        cleanupTasks.push(new CleanupTask(description, action));
    }

    /**
     * Store data in cache
     */
    public void cache(String key, Object value) {
        cache.put(key, value);
    }

    /**
     * Retrieve cached data
     */
    @SuppressWarnings("unchecked")
    public <T> T getCached(String key, Class<T> type) {
        return (T) cache.get(key);
    }

    /**
     * Increment seeding stat
     */
    public void incrementStat(String key) {
        seedingStats.merge(key, 1, Integer::sum);
    }

    /**
     * Get seeding stats
     */
    public Map<String, Integer> getStats() {
        return new HashMap<>(seedingStats);
    }

    public RequestExecutor getExecutor() {
        return executor;
    }

    @lombok.Value
    public static class CleanupTask {
        String description;
        Runnable action;
    }
}