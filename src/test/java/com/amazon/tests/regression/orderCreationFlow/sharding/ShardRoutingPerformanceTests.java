package com.amazon.tests.regression.orderCreationFlow.sharding;

import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.testng.Assert.assertTrue;

@Slf4j
@Test(groups = "sharding")
public class ShardRoutingPerformanceTests extends AbstractShardTest {

    private static final long SINGLE_ORDER_P95_THRESHOLD_MS = 500;
    private static final long CONCURRENT_THROUGHPUT_MIN_ORDERS_PER_SEC = 20;

    // ---------- 6. Routing overhead vs. SLA threshold ----------

    @Test(description = "Order creation latency under the sharded profile should stay within SLA threshold (p95)", priority = 1)
    public void testShardRouting_LatencyWithinSlaThreshold() {

        int iterations = 30;
        List<Long> latenciesMs = new ArrayList<>();

        for (int i = 0; i < iterations; i++) {
            String userId = UUID.randomUUID().toString();
            String idempotencyKey = UUID.randomUUID().toString();

            long start = System.nanoTime();
            orderApiClient.createOrder(userId, idempotencyKey, sharedProduct);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            latenciesMs.add(elapsedMs);
        }

        latenciesMs.sort(Long::compareTo);
        long p95 = latenciesMs.get((int) Math.ceil(0.95 * latenciesMs.size()) - 1);

        logStep("p95 latency over " + iterations + " orders: " + p95 + "ms");

        assertTrue(p95 <= SINGLE_ORDER_P95_THRESHOLD_MS,
                "p95 latency " + p95 + "ms exceeded threshold " + SINGLE_ORDER_P95_THRESHOLD_MS + "ms");
    }

    // ---------- 7. Cross-shard concurrent write throughput ----------

    @Test(description = "Concurrent order creation spread across all shards should meet minimum throughput", priority = 2)
    public void testCrossShardWrite_Throughput_ConcurrentUsers() throws InterruptedException {

        int concurrentRequests = 100;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        List<Long> failures = Collections.synchronizedList(new ArrayList<>());

        long start = System.nanoTime();

        for (int i = 0; i < concurrentRequests; i++) {
            pool.submit(() -> {
                try {
                    String userId = UUID.randomUUID().toString();
                    String idempotencyKey = UUID.randomUUID().toString();
                    orderApiClient.createOrder(userId, idempotencyKey, sharedProduct);
                } catch (Exception e) {
                    failures.add(System.nanoTime());
                    log.warn("Order creation failed during throughput test: {}", e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        long deadline = System.currentTimeMillis() + 60_000;
        while (latch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        boolean completed = latch.getCount() == 0;

        long elapsedSec = Math.max((System.nanoTime() - start) / 1_000_000_000, 1);
        pool.shutdown();

        assertTrue(completed, "Not all requests completed within 60s timeout");

        double throughput = (double) (concurrentRequests - failures.size()) / elapsedSec;
        logStep("Throughput: " + throughput + " orders/sec, failures: " + failures.size());

        assertTrue(failures.isEmpty(), failures.size() + " requests failed under concurrent cross-shard load");
        assertTrue(throughput >= CONCURRENT_THROUGHPUT_MIN_ORDERS_PER_SEC,
                "Throughput " + throughput + " orders/sec below minimum " + CONCURRENT_THROUGHPUT_MIN_ORDERS_PER_SEC);
    }

    // ---------- 8. Per-shard connection pool isolation ----------

    @Test(description = "Saturating one shard's connection pool should not degrade response times on other shards", priority = 3)
    public void testShardRouter_PerShardConnectionPoolIsolation() throws InterruptedException {

        int targetShardForSaturation = 0;
        int otherShard = 1;
        int saturatingRequests = 40; // comfortably exceeds the configured Hikari pool size

        ExecutorService saturationPool = Executors.newFixedThreadPool(saturatingRequests);
        CountDownLatch saturationLatch = new CountDownLatch(saturatingRequests);

        for (int i = 0; i < saturatingRequests; i++) {
            saturationPool.submit(() -> {
                try {
                    String userId = shardKeyResolver.generateUserIdForShard(targetShardForSaturation);
                    orderApiClient.createOrder(userId, UUID.randomUUID().toString(), sharedProduct);
                } catch (Exception ignored) {
                } finally {
                    saturationLatch.countDown();
                }
            });
        }

        // Measure a different-shard request's latency WHILE shard-0 is saturated
        long start = System.nanoTime();
        String otherShardUserId = shardKeyResolver.generateUserIdForShard(otherShard);
        orderApiClient.createOrder(otherShardUserId, UUID.randomUUID().toString(), sharedProduct);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        long deadline = System.currentTimeMillis() + 30_000;
        while (saturationLatch.getCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        saturationPool.shutdown();

        logStep("Shard " + otherShard + " latency during shard " + targetShardForSaturation + " saturation: " + elapsedMs + "ms");

        assertTrue(elapsedMs <= SINGLE_ORDER_P95_THRESHOLD_MS,
                "Shard " + otherShard + " request took " + elapsedMs + "ms during shard " + targetShardForSaturation
                        + " saturation — pool isolation may be leaking");
    }
}