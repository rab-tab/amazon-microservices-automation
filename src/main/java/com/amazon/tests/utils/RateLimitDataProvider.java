package com.amazon.tests.utils;


import com.amazon.tests.config.RateLimitConfig;
import org.testng.annotations.DataProvider;

/**
 * Centralized DataProvider for Rate Limiting Tests
 *
 * Contains all test scenarios for both IP-based and user-based rate limiting.
 * This separation makes it easy to:
 * - Add new test scenarios in one place
 * - Reuse data providers across multiple test classes
 * - Maintain test data independently from test logic
 */
public class RateLimitDataProvider {

    /**
     * Data provider for IP-based rate limiting scenarios (anonymous endpoints)
     *
     * These tests verify rate limiting for unauthenticated requests based on IP address.
     *
     * @return Array of RateLimitConfig objects for IP-based tests
     */
    @DataProvider(name = "ipBasedScenarios")
    public static Object[][] ipBasedScenarios() {
        return new Object[][] {
                { RateLimitConfig.IPBased.REGISTRATION },
                { RateLimitConfig.IPBased.LOGIN },
                { RateLimitConfig.IPBased.PRODUCTS_LIST }
        };
    }

    /**
     * Data provider for user-based rate limiting scenarios (authenticated endpoints)
     *
     * These tests verify rate limiting for authenticated requests based on user identity.
     *
     * @return Array of RateLimitConfig objects for user-based tests
     */
    @DataProvider(name = "userBasedScenarios")
    public static Object[][] userBasedScenarios() {
        return new Object[][] {
                { RateLimitConfig.UserBased.ORDER_LIST },
                { RateLimitConfig.UserBased.ORDER_CREATION },
                { RateLimitConfig.UserBased.PROFILE_UPDATE }
        };
    }

    /**
     * Data provider for all rate limiting scenarios (both IP-based and user-based)
     *
     * Use this when you want to run a test against all scenarios regardless of type.
     *
     * @return Array of RateLimitConfig objects for all tests
     */
    @DataProvider(name = "allRateLimitScenarios")
    public static Object[][] allRateLimitScenarios() {
        return new Object[][] {
                // IP-based scenarios
                {RateLimitConfig.IPBased.REGISTRATION },
                {RateLimitConfig.IPBased.LOGIN },
                {RateLimitConfig.IPBased.PRODUCTS_LIST },

                // User-based scenarios
                {RateLimitConfig.UserBased.ORDER_LIST },
                {RateLimitConfig.UserBased.ORDER_CREATION },
                {RateLimitConfig.UserBased.PROFILE_UPDATE }
        };
    }

    /**
     * Data provider for burst capacity tests
     *
     * Returns only scenarios with specific burst capacity characteristics
     * useful for focused burst testing.
     *
     * @return Array of RateLimitConfig objects for burst tests
     */
    @DataProvider(name = "burstScenarios")
    public static Object[][] burstScenarios() {
        return new Object[][] {
                {RateLimitConfig.IPBased.REGISTRATION },      // Small burst: 10
                {RateLimitConfig.IPBased.PRODUCTS_LIST },     // Large burst: 100
                {RateLimitConfig.UserBased.ORDER_LIST }       // Medium burst: 10
        };
    }

    /**
     * Data provider for high-volume scenarios
     *
     * Returns scenarios with high request rates for performance testing.
     *
     * @return Array of RateLimitConfig objects for high-volume tests
     */
    @DataProvider(name = "highVolumeScenarios")
    public static Object[][] highVolumeScenarios() {
        return new Object[][] {
                {RateLimitConfig.IPBased.PRODUCTS_LIST },     // 50 req/sec, burst: 100
                {RateLimitConfig.IPBased.LOGIN }              // 10 req/sec, burst: 20
        };
    }

    /**
     * Data provider for low-volume scenarios
     *
     * Returns scenarios with low request rates for precise testing.
     *
     * @return Array of RateLimitConfig objects for low-volume tests
     */
    @DataProvider(name = "lowVolumeScenarios")
    public static Object[][] lowVolumeScenarios() {
        return new Object[][] {
                {RateLimitConfig.IPBased.REGISTRATION },          // 5 req/sec
                {RateLimitConfig.UserBased.ORDER_CREATION },      // 0.166 req/sec
                {RateLimitConfig.UserBased.PROFILE_UPDATE }       // 0.083 req/sec
        };
    }
}