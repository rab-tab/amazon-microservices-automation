package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Utility to verify Redis cache state in tests.
 * Uses Jedis to directly query the cache and assert
 * that keys exist/expire correctly.
 */
@Slf4j
public class RedisValidator {

    private static final String REDIS_HOST = System.getProperty("redis.host", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(System.getProperty("redis.port", "6379"));
    private static final String REDIS_PASSWORD = System.getProperty("redis.password", "redis123");

    private static JedisPool pool;

    static {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxIdle(4);
        config.setMinIdle(1);
        pool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000, REDIS_PASSWORD);
    }

    public static boolean keyExists(String key) {
        try (Jedis jedis = pool.getResource()) {
            boolean exists = jedis.exists(key);
            log.debug("Redis key '{}' exists: {}", key, exists);
            return exists;
        } catch (Exception e) {
            log.warn("Redis check failed for key '{}': {}", key, e.getMessage());
            return false;
        }
    }

    public static boolean userCacheExists(String userId) {
        return keyExists("user:" + userId);
    }

    public static boolean productCacheExists(String productId) {
        return keyExists("products::" + productId);
    }

    public static long getTtl(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.ttl(key);
        } catch (Exception e) {
            log.warn("Redis TTL check failed for key '{}': {}", key, e.getMessage());
            return -1;
        }
    }

    public static String getValue(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            log.warn("Redis get failed for key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    public static long keyCount(String pattern) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.keys(pattern).size();
        } catch (Exception e) {
            log.warn("Redis key scan failed for pattern '{}': {}", pattern, e.getMessage());
            return 0;
        }
    }

    public static boolean isRedisUp() {
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public static void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
    }
}
