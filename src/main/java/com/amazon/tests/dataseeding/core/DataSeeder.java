package com.amazon.tests.dataseeding.core;

// DataSeeder.java


/**
 * Base interface for all data seeders
 *
 * @param <T> Type of data being seeded
 */
public interface DataSeeder<T> {

    /**
     * Seed data and return the result
     *
     * @return Seeded data
     * @throws SeedingException if seeding fails
     */
    T seed() throws SeedingException;

    /**
     * Cleanup seeded data
     */
    void cleanup();

    /**
     * Get the namespace for this seeder
     *
     * @return namespace string
     */
    String getNamespace();

    /**
     * Check if data has been seeded
     *
     * @return true if seeded, false otherwise
     */
    boolean isSeeded();
}