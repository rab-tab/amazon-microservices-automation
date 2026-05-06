package com.amazon.tests.dataseeding.core;

// BaseSeedingManager.java

import lombok.extern.slf4j.Slf4j;

/**
 * Abstract base class for all seeders
 * Provides common functionality and enforces contract
 */
@Slf4j
public abstract class BaseSeedingManager<T> implements DataSeeder<T> {

    protected final SeedingContext context;
    protected T seededData;
    protected boolean seeded;

    protected BaseSeedingManager(SeedingContext context) {
        this.context = context;
        this.seeded = false;
    }

    @Override
    public final T seed() throws SeedingException {
        if (seeded) {
            log.warn("Data already seeded for {}, returning existing data", this.getClass().getSimpleName());
            return seededData;
        }

        try {
            log.info("Starting seeding: {}", this.getClass().getSimpleName());
            long startTime = System.currentTimeMillis();

            // Call template method
            seededData = doSeed();
            seeded = true;

            long duration = System.currentTimeMillis() - startTime;
            log.info("Seeding completed: {} in {}ms", this.getClass().getSimpleName(), duration);

            return seededData;

        } catch (Exception e) {
            log.error("Seeding failed: {}", this.getClass().getSimpleName(), e);
            throw new SeedingException("Failed to seed data", e);
        }
    }

    /**
     * Template method - implement actual seeding logic
     */
    protected abstract T doSeed() throws Exception;

    @Override
    public final void cleanup() {
        if (!seeded) {
            log.debug("No data to cleanup for {}", this.getClass().getSimpleName());
            return;
        }

        try {
            log.info("Starting cleanup: {}", this.getClass().getSimpleName());
            doCleanup();
            seeded = false;
            seededData = null;
            log.info("Cleanup completed: {}", this.getClass().getSimpleName());

        } catch (Exception e) {
            log.error("Cleanup failed: {}", this.getClass().getSimpleName(), e);
        }
    }

    /**
     * Template method - implement cleanup logic
     */
    protected abstract void doCleanup();

    @Override
    public String getNamespace() {
        return context.getNamespace();
    }

    @Override
    public boolean isSeeded() {
        return seeded;
    }

    /**
     * Wait for eventual consistency
     */
    protected void waitForPropagation(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Wait interrupted", e);
        }
    }
}
