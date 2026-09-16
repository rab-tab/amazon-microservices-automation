package com.amazon.tests.utils;

import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Utility to verify Redis cache state in tests.
 * Uses Jedis to directly query the cache and assert
 * that keys exist/expire correctly.
 *
 * ⭐ CLUSTER SUPPORT
 * Two modes, chosen at class-load time via -Dredis.cluster=true:
 * - STANDALONE (default, unchanged from before): a single JedisPool against
 *   one host:port. This is a plain client with no cluster awareness — if
 *   pointed at one node of a real Redis Cluster, it will silently report
 *   "not found" for any key living on a DIFFERENT node, not an error. This
 *   caused testMutualExclusionUnderConcurrency to fail against the cluster
 *   even though the lock genuinely existed — just on a node this client
 *   never looked at.
 * - CLUSTER (redis.cluster=true): a JedisCluster built from seed nodes
 *   (redis.cluster.nodes, comma-separated host:port), which understands
 *   cluster topology and follows MOVED/ASK redirects automatically.
 *
 * For single-instance test runs (the `test` Spring profile), leave
 * redis.cluster unset — behavior is identical to before this change. For
 * cluster runs (`cluster-test` profile), pass -Dredis.cluster=true
 * (and -Dredis.cluster.nodes=... if the defaults below don't match your
 * setup).
 */
@Slf4j
public class RedisValidator {

    private static final String REDIS_HOST = System.getProperty("redis.host", "localhost");
    private static final int REDIS_PORT = Integer.parseInt(System.getProperty("redis.port", "6379"));
    private static final String REDIS_PASSWORD = System.getProperty("redis.password", "redis123");

    private static final boolean CLUSTER_MODE = Boolean.parseBoolean(System.getProperty("redis.cluster", "false"));
    private static final String CLUSTER_NODES = System.getProperty(
            "redis.cluster.nodes",
            "localhost:7000,localhost:7001,localhost:7002,localhost:7003,localhost:7004,localhost:7005");

    private static JedisPool pool;       // standalone mode
    private static JedisCluster cluster; // cluster mode

    static {
        if (CLUSTER_MODE) {
            Set<HostAndPort> nodes = new HashSet<>();
            for (String hostPort : CLUSTER_NODES.split(",")) {
                String[] parts = hostPort.trim().split(":");
                nodes.add(new HostAndPort(parts[0], Integer.parseInt(parts[1])));
            }
            cluster = new JedisCluster(nodes, 2000);
            log.info("RedisValidator initialized in CLUSTER mode — seed nodes: {}", CLUSTER_NODES);
        } else {
            JedisPoolConfig config = new JedisPoolConfig();
            config.setMaxTotal(8);
            config.setMaxIdle(4);
            config.setMinIdle(1);
            //pool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000, REDIS_PASSWORD);
            pool = new JedisPool(config, REDIS_HOST, REDIS_PORT, 2000);
            log.info("RedisValidator initialized in STANDALONE mode — {}:{}", REDIS_HOST, REDIS_PORT);
        }
    }

    public static boolean keyExists(String key) {
        if (CLUSTER_MODE) {
            try {
                boolean exists = cluster.exists(key);
                log.debug("Redis key '{}' exists: {}", key, exists);
                return exists;
            } catch (Exception e) {
                log.warn("Redis check failed for key '{}': {}", key, e.getMessage());
                return false;
            }
        }
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
        if (CLUSTER_MODE) {
            try {
                return cluster.ttl(key);
            } catch (Exception e) {
                log.warn("Redis TTL check failed for key '{}': {}", key, e.getMessage());
                return -1;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.ttl(key);
        } catch (Exception e) {
            log.warn("Redis TTL check failed for key '{}': {}", key, e.getMessage());
            return -1;
        }
    }

    public static String getValue(String key) {
        if (CLUSTER_MODE) {
            try {
                return cluster.get(key);
            } catch (Exception e) {
                log.warn("Redis get failed for key '{}': {}", key, e.getMessage());
                return null;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key);
        } catch (Exception e) {
            log.warn("Redis get failed for key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Counts keys matching a pattern.
     *
     * ⚠️ CLUSTER MODE CAVEAT: KEYS is not a cluster-wide operation — Redis
     * Cluster has no single command that scans every node at once. This
     * iterates each master node's own connection and sums the per-node
     * matches, which works but is O(all keys on all nodes) same as
     * standalone KEYS, just repeated per master. Fine for test-scale data;
     * do not rely on this for anything at production scale.
     */
    public static long keyCount(String pattern) {
        if (CLUSTER_MODE) {
            try {
                long total = 0;
                Map<String, ?> clusterNodes = cluster.getClusterNodes();
                for (String nodeKey : clusterNodes.keySet()) {
                    // nodeKey is "host:port" — open a direct connection to that
                    // node specifically, since JedisCluster itself doesn't
                    // expose a cluster-wide KEYS.
                    String[] parts = nodeKey.split(":");
                    try (Jedis nodeJedis = new Jedis(parts[0], Integer.parseInt(parts[1]))) {
                        total += nodeJedis.keys(pattern).size();
                    } catch (Exception nodeError) {
                        log.warn("Redis key scan failed against node '{}': {}", nodeKey, nodeError.getMessage());
                    }
                }
                return total;
            } catch (Exception e) {
                log.warn("Redis key scan failed for pattern '{}': {}", pattern, e.getMessage());
                return 0;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.keys(pattern).size();
        } catch (Exception e) {
            log.warn("Redis key scan failed for pattern '{}': {}", pattern, e.getMessage());
            return 0;
        }
    }

    public static void deleteKey(String key) {
        if (CLUSTER_MODE) {
            try {
                cluster.del(key);
                log.debug("Deleted Redis key '{}'", key);
            } catch (Exception e) {
                log.warn("Failed to delete Redis key '{}': {}", key, e.getMessage());
            }
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key);
            log.debug("Deleted Redis key '{}'", key);
        } catch (Exception e) {
            log.warn("Failed to delete Redis key '{}': {}", key, e.getMessage());
        }
    }

    public static boolean isRedisUp() {
        if (CLUSTER_MODE) {
            try {
                // JedisCluster has no direct PING; a successful (even negative)
                // exists() call against any key proves connectivity + a working
                // topology map, without depending on any specific key existing.
                cluster.exists("__redisvalidator_connectivity_check__");
                return true;
            } catch (Exception e) {
                return false;
            }
        }
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Cluster-mode only: the hash slot (0-16383) a given key maps to. Returns
     * null in standalone mode (there's only ever one node, slots don't apply).
     * Two keys returning the same slot number means they're co-located on the
     * same node — that's the whole answer the "does cache key and lock key
     * share a slot" diagnostic needs.
     *
     * Implemented directly (CRC16/XMODEM per the Redis Cluster spec) rather
     * than depending on a Jedis-internal utility class, since that internal
     * API's exact path isn't guaranteed stable across Jedis versions. This
     * is the same algorithm `redis-cli CLUSTER KEYSLOT` uses, including hash
     * tag ({@code {tag}}) support.
     */
    public static Integer clusterKeySlot(String key) {
        if (!CLUSTER_MODE) {
            return null;
        }
        try {
            String hashable = extractHashTag(key);
            return crc16(hashable) % 16384;
        } catch (Exception e) {
            log.warn("Could not compute cluster slot for key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Per the Redis Cluster spec: if a key contains "{...}", only the
     * content inside the braces is hashed (this is how callers force
     * multiple keys onto the same slot). If there's no "{" or no matching
     * non-empty "}" after it, the whole key is used as-is.
     */
    private static String extractHashTag(String key) {
        int start = key.indexOf('{');
        if (start < 0) {
            return key;
        }
        int end = key.indexOf('}', start + 1);
        if (end < 0 || end == start + 1) {
            return key;
        }
        return key.substring(start + 1, end);
    }

    // Standard CRC16/XMODEM lookup table, as specified by the Redis Cluster
    // spec for computing key hash slots.
    private static final int[] CRC16_TABLE = {
            0x0000,0x1021,0x2042,0x3063,0x4084,0x50a5,0x60c6,0x70e7,
            0x8108,0x9129,0xa14a,0xb16b,0xc18c,0xd1ad,0xe1ce,0xf1ef,
            0x1231,0x0210,0x3273,0x2252,0x52b5,0x4294,0x72f7,0x62d6,
            0x9339,0x8318,0xb37b,0xa35a,0xd3bd,0xc39c,0xf3ff,0xe3de,
            0x2462,0x3443,0x0420,0x1401,0x64e6,0x74c7,0x44a4,0x5485,
            0xa56a,0xb54b,0x8528,0x9509,0xe5ee,0xf5cf,0xc5ac,0xd58d,
            0x3653,0x2672,0x1611,0x0630,0x76d7,0x66f6,0x5695,0x46b4,
            0xb75b,0xa77a,0x9719,0x8738,0xf7df,0xe7fe,0xd79d,0xc7bc,
            0x48c4,0x58e5,0x6886,0x78a7,0x0840,0x1861,0x2802,0x3823,
            0xc9cc,0xd9ed,0xe98e,0xf9af,0x8948,0x9969,0xa90a,0xb92b,
            0x5af5,0x4ad4,0x7ab7,0x6a96,0x1a71,0x0a50,0x3a33,0x2a12,
            0xdbfd,0xcbdc,0xfbbf,0xeb9e,0x9b79,0x8b58,0xbb3b,0xab1a,
            0x6ca6,0x7c87,0x4ce4,0x5cc5,0x2c22,0x3c03,0x0c60,0x1c41,
            0xedae,0xfd8f,0xcdec,0xddcd,0xad2a,0xbd0b,0x8d68,0x9d49,
            0x7e97,0x6eb6,0x5ed5,0x4ef4,0x3e13,0x2e32,0x1e51,0x0e70,
            0xff9f,0xefbe,0xdfdd,0xcffc,0xbf1b,0xaf3a,0x9f59,0x8f78,
            0x9188,0x81a9,0xb1ca,0xa1eb,0xd10c,0xc12d,0xf14e,0xe16f,
            0x1080,0x00a1,0x30c2,0x20e3,0x5004,0x4025,0x7046,0x6067,
            0x83b9,0x9398,0xa3fb,0xb3da,0xc33d,0xd31c,0xe37f,0xf35e,
            0x02b1,0x1290,0x22f3,0x32d2,0x4235,0x5214,0x6277,0x7256,
            0xb5ea,0xa5cb,0x95a8,0x8589,0xf56e,0xe54f,0xd52c,0xc50d,
            0x34e2,0x24c3,0x14a0,0x0481,0x7466,0x6447,0x5424,0x4405,
            0xa7db,0xb7fa,0x8799,0x97b8,0xe75f,0xf77e,0xc71d,0xd73c,
            0x26d3,0x36f2,0x0691,0x16b0,0x6657,0x7676,0x4615,0x5634,
            0xd94c,0xc96d,0xf90e,0xe92f,0x99c8,0x89e9,0xb98a,0xa9ab,
            0x5844,0x4865,0x7806,0x6827,0x18c0,0x08e1,0x3882,0x28a3,
            0xcb7d,0xdb5c,0xeb3f,0xfb1e,0x8bf9,0x9bd8,0xabbb,0xbb9a,
            0x4a75,0x5a54,0x6a37,0x7a16,0x0af1,0x1ad0,0x2ab3,0x3a92,
            0xfd2e,0xed0f,0xdd6c,0xcd4d,0xbdaa,0xad8b,0x9de8,0x8dc9,
            0x7c26,0x6c07,0x5c64,0x4c45,0x3ca2,0x2c83,0x1ce0,0x0cc1,
            0xef1f,0xff3e,0xcf5d,0xdf7c,0xaf9b,0xbfba,0x8fd9,0x9ff8,
            0x6e17,0x7e36,0x4e55,0x5e74,0x2e93,0x3eb2,0x0ed1,0x1ef0
    };

    private static int crc16(String key) {
        int crc = 0;
        byte[] bytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            crc = ((crc << 8) ^ CRC16_TABLE[((crc >> 8) ^ (b & 0xff)) & 0xff]) & 0xffff;
        }
        return crc;
    }

    public static void close() {
        if (pool != null && !pool.isClosed()) {
            pool.close();
        }
        if (cluster != null) {
            try {
                cluster.close();
            } catch (Exception e) {
                log.warn("Error closing JedisCluster: {}", e.getMessage());
            }
        }
    }
}