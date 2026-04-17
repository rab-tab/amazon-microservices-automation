package com.amazon.tests.config;

import lombok.Builder;
import lombok.Data;

/**
 * Configuration for rate limit test scenarios
 */
@Data
@Builder
public class RateLimitConfig {

    private String testName;
    private String endpoint;
    private String requestBodyTemplate;
    private String httpMethod;
    private int totalRequests;
    private int threadPoolSize;
    private double replenishRate;       // tokens per second
    private int burstCapacity;          // max tokens in bucket
    private int expectedSuccess;
    private int expectedRejected;
    private boolean requiresAuth;

    // Tolerance for timing-based test variations
    private int tolerance;

    /**
     * Pre-configured scenarios for IP-based rate limiting
     */
    public static class IPBased {

        public static final RateLimitConfig REGISTRATION = RateLimitConfig.builder()
                .testName("Registration Rate Limit")
                .endpoint("/api/users/register")
                .requestBodyTemplate(null)  // Will be generated dynamically per request
                .httpMethod("POST")
                .totalRequests(20)
                .threadPoolSize(20)
                .replenishRate(5)        // 5 req/sec
                .burstCapacity(10)       // burst: 10
                .expectedSuccess(10)
                .expectedRejected(10)
                .requiresAuth(false)     // Registration should be public/anonymous
                .tolerance(2)
                .build();

        public static final RateLimitConfig LOGIN = RateLimitConfig.builder()
                .testName("Login Rate Limit")
                .endpoint("/api/users/login")
                .requestBodyTemplate(null)  // Will be generated dynamically using createLoginRequest
                .httpMethod("POST")
                .totalRequests(30)
                .threadPoolSize(30)
                .replenishRate(10)       // 10 req/sec
                .burstCapacity(20)       // burst: 20
                .expectedSuccess(20)
                .expectedRejected(10)
                .requiresAuth(false)
                .tolerance(2)
                .build();

        public static final RateLimitConfig PRODUCTS_LIST = RateLimitConfig.builder()
                .testName("Products List Rate Limit")
                .endpoint("/api/products")
                .requestBodyTemplate(null)  // GET request
                .httpMethod("GET")
                .totalRequests(150)
                .threadPoolSize(150)
                .replenishRate(50)       // 50 req/sec
                .burstCapacity(100)      // burst: 100
                .expectedSuccess(100)
                .expectedRejected(50)
                .requiresAuth(false)
                .tolerance(3)
                .build();
    }

    /**
     * Pre-configured scenarios for user-based rate limiting
     */
    public static class UserBased {

        public static final RateLimitConfig ORDER_LIST = RateLimitConfig.builder()
                .testName("Order List Rate Limit")
                .endpoint("/api/orders")
                .requestBodyTemplate(null)
                .httpMethod("GET")
                .totalRequests(15)
                .threadPoolSize(15)
                .replenishRate(1)        // 60/min = 1/sec
                .burstCapacity(10)
                .expectedSuccess(10)
                .expectedRejected(5)
                .requiresAuth(true)
                .tolerance(2)
                .build();

        public static final RateLimitConfig ORDER_CREATION = RateLimitConfig.builder()
                .testName("Order Creation Rate Limit")
                .endpoint("/api/orders")
                .requestBodyTemplate("{\"productId\":\"P123\",\"quantity\":1}")
                .httpMethod("POST")
                .totalRequests(10)
                .threadPoolSize(10)
                .replenishRate(0.166)    // 10/min ≈ 0.166/sec
                .burstCapacity(5)
                .expectedSuccess(5)
                .expectedRejected(5)
                .requiresAuth(true)
                .tolerance(1)
                .build();

        public static final RateLimitConfig PROFILE_UPDATE = RateLimitConfig.builder()
                .testName("Profile Update Rate Limit")
                .endpoint("/api/users/profile")
                .requestBodyTemplate("{\"name\":\"Updated Name\"}")
                .httpMethod("PUT")
                .totalRequests(8)
                .threadPoolSize(8)
                .replenishRate(0.083)    // 5/min ≈ 0.083/sec
                .burstCapacity(3)
                .expectedSuccess(3)
                .expectedRejected(5)
                .requiresAuth(true)
                .tolerance(1)
                .build();
    }

    /**
     * Validation helper
     */
    public boolean isValid() {
        return testName != null
                && endpoint != null
                && httpMethod != null
                && totalRequests > 0
                && threadPoolSize > 0
                && burstCapacity > 0
                && expectedSuccess >= 0
                && expectedRejected >= 0
                && (expectedSuccess + expectedRejected) <= totalRequests;
    }
}