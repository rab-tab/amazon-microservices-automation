package com.amazon.tests.regression.orderCreationFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distributed lock correctness tests against a REAL Redis Cluster (3
 * masters + 3 replicas — see scripts/redis-cluster/README.md) instead of
 * a single instance. Requires order-service running with the
 * `cluster-test` Spring profile active, NOT `test` — see
 * scripts/redis-cluster/README.md and application-cluster-test.yaml.
 *
 * STRICTLY FUNCTIONAL — no failover, no node kills, no injected faults.
 * Chaos scenarios against this cluster (graceful CLUSTER FAILOVER, hard
 * node kills, reproducing the lock-duplication failover window) live in
 * the separate chaos framework, not here.
 *
 * ⭐ CLUSTER-SPECIFIC CONSIDERATION, not present in the single-instance
 * version of this class: OrderIdempotencyService.buildKey() does not use a
 * Redis Cluster hash tag ({@code {tag}} syntax), so the cache key
 * ({@code idempotency:order:{userId}:{key}}) and its lock key
 * ({@code ...{key}:lock}) are two different full strings and can land on
 * completely different cluster nodes/shards. Every operation on them today
 * is single-key, so nothing currently breaks from this — but it means the
 * two keys can fail over independently of each other, a failure window
 * that's structurally impossible in single-instance mode. This class
 * includes a diagnostic test that documents current behavior rather than
 * asserting a specific outcome, since whether this needs fixing (hash
 * tags) or is an accepted tradeoff is a design question for the team, not
 * something to bake into a pass/fail assertion yet.
 *
 * ⚠️ DEPENDENCY CHECK NEEDED: RedisValidator was originally built against
 * the single-instance Toxiproxy setup. If it doesn't handle cluster MOVED
 * redirects the way `redis-cli -c` does, keyExists()/getTtl() calls here
 * may silently report "not found" for keys that live on a different node
 * than whatever RedisValidator's underlying client defaults to. Confirm
 * this before trusting a failing assertion as a real bug.
 */
@Slf4j
@Epic("Order Service")
@Feature("Distributed Locking - Cluster")
public class OrderDistributedLockClusterTest extends BaseTest {

    private static final int LOCK_TTL_SECONDS = 30; // must match OrderIdempotencyService.LOCK_TTL_SECONDS

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    private String cacheKey(String userId, String idempotencyKey) {
        return "idempotency:order:" + userId + ":" + idempotencyKey;
    }

    private String lockKey(String userId, String idempotencyKey) {
        return cacheKey(userId, idempotencyKey) + ":lock";
    }

    // ══════════════════════════════════════════════════════════════
    // 1. LOCK RELEASED ON SUCCESS — same assertion as single-instance,
    //    now proving it holds when the key could live on any of 6 nodes
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Lock is released after a successful order creation, regardless of which cluster node owns the key")
    @Story("Distributed Lock - Release on Success (Cluster)")
    @Severity(SeverityLevel.CRITICAL)
    public void testLockReleasedOnSuccess() {
        logStep("TEST: Lock should not exist anywhere in the cluster after a successful order creation");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order = orderApiClient(token).createOrder(userId, idempotencyKey, purchase.getProducts());
        logStep("  ✓ Order created: " + order.getId());

        String lockKey = lockKey(userId, idempotencyKey);
        assertThat(RedisValidator.keyExists(lockKey))
                .as("Lock should be released regardless of which node owns its hash slot, " +
                        "not left to expire via its " + LOCK_TTL_SECONDS + "s TTL")
                .isFalse();

        logStep("✅ Lock correctly released on success");
    }

    // ══════════════════════════════════════════════════════════════
    // 2. LOCK RELEASED ON DUPLICATE DETECTION — same as single-instance
    // ══════════════════════════════════════════════════════════════

    @Test(description = "The duplicate-detection path also releases its own lock cleanly against the cluster")
    @Story("Distributed Lock - Release on Success (Cluster)")
    @Severity(SeverityLevel.CRITICAL)
    public void testLockReleasedOnDuplicateDetection() {
        logStep("TEST: Lock should not exist after a duplicate request is detected and returned");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse first = orderApiClient(token).createOrder(userId, idempotencyKey, purchase.getProducts());
        logStep("  ✓ First order created: " + first.getId());

        String lockKey = lockKey(userId, idempotencyKey);
        assertThat(RedisValidator.keyExists(lockKey)).isFalse();

        TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();
        ServiceResponse duplicateResponse = orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(200);

        assertThat(RedisValidator.keyExists(lockKey))
                .as("Duplicate-detection path should release its own lock immediately")
                .isFalse();

        logStep("✅ Duplicate-detection path's own lock cycle also releases cleanly against the cluster");
    }

    // ══════════════════════════════════════════════════════════════
    // 3. MUTUAL EXCLUSION UNDER CONCURRENCY — same as single-instance,
    //    now against real cluster routing
    // ══════════════════════════════════════════════════════════════

    /**
     * Same best-effort caveat as the single-instance version: external
     * polling can't prove exclusivity at every instant, only that the lock
     * was observed held at least once and is released once everything
     * settles.
     */
    @Test(description = "Lock is observed held during a concurrent same-key race against the cluster, and released once all requests complete")
    @Story("Distributed Lock - Mutual Exclusion (Cluster)")
    @Severity(SeverityLevel.NORMAL)
    public void testMutualExclusionUnderConcurrency() throws Exception {
        logStep("TEST: Lock should be observed held during concurrent race against the cluster, released after");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        String lockKey = lockKey(userId, idempotencyKey);
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();

        int concurrentCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrentCount);
        List<ServiceResponse> responses = new CopyOnWriteArrayList<>();
        AtomicBoolean lockObservedHeld = new AtomicBoolean(false);
        AtomicBoolean monitorRunning = new AtomicBoolean(true);

        Thread monitor = new Thread(() -> {
            while (monitorRunning.get()) {
                if (RedisValidator.keyExists(lockKey)) {
                    lockObservedHeld.set(true);
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        monitor.start();

        for (int i = 0; i < concurrentCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    responses.add(orderApiClient(token)
                            .createOrderWithFault(userId, idempotencyKey, orderRequest, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();
        executor.shutdown();

        monitorRunning.set(false);
        monitor.join(1000);

        assertThat(lockObservedHeld.get())
                .as("Monitor should have observed the lock existing at least once during the race")
                .isTrue();

        assertThat(RedisValidator.keyExists(lockKey))
                .as("Lock should be released once all requests have completed")
                .isFalse();

        long uniqueOrders = responses.stream()
                .map(r -> r.as(TestModels.OrderResponse.class).getId())
                .distinct()
                .count();
        assertThat(uniqueOrders).as("All concurrent requests should still resolve to exactly 1 order").isEqualTo(1);

        logStep("✅ Lock observed held during race against the cluster, released after — 1 order from " +
                concurrentCount + " concurrent requests");
    }

    // ══════════════════════════════════════════════════════════════
    // 4. CLUSTER-SPECIFIC: does the cache key and its lock key share a
    //    hash slot, or land on different nodes? — DIAGNOSTIC, not
    //    pass/fail, since neither answer is currently a proven bug
    // ══════════════════════════════════════════════════════════════

    /**
     * Documents whether the cache key and lock key land on the same cluster
     * slot or different ones, using RedisValidator.clusterKeySlot(). Still
     * no hard pass/fail on the slot comparison itself — same-slot vs
     * different-slot are both valid states, not a bug either way; whether to
     * add a hash tag is a design decision for the team (see class Javadoc),
     * not something to bake into red/green here. The two isNotNull() checks
     * ARE real assertions, though — they catch the suite accidentally
     * running without -Dredis.cluster=true, in which case clusterKeySlot()
     * returns null by design and this test would otherwise silently report
     * nothing useful.
     */
    @Test(description = "Document whether an idempotency key's cache entry and lock entry land on the same cluster node or different ones")
    @Story("Distributed Lock - Cluster Key Distribution")
    @Severity(SeverityLevel.MINOR)
    public void testDocumentCacheAndLockKeyNodeDistribution() {
        logStep("DIAGNOSTIC TEST: checking whether cache key and lock key share a hash slot");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        String cacheKey = cacheKey(userId, idempotencyKey);
        String lockKey = lockKey(userId, idempotencyKey);

        Integer cacheSlot = RedisValidator.clusterKeySlot(cacheKey);
        Integer lockSlot = RedisValidator.clusterKeySlot(lockKey);

        assertThat(cacheSlot)
                .as("clusterKeySlot() returned null — suite likely running without -Dredis.cluster=true")
                .isNotNull();
        assertThat(lockSlot)
                .as("clusterKeySlot() returned null — suite likely running without -Dredis.cluster=true")
                .isNotNull();

        logStep("  Cache key: " + cacheKey + " → slot " + cacheSlot);
        logStep("  Lock key:  " + lockKey + " → slot " + lockSlot);
    }
        // ══════════════════════════════════════════════════════════════
        // 5. NEGATIVE: REJECTED REQUEST NEVER ORPHANS A LOCK
        // ══════════════════════════════════════════════════════════════

        /**
         * Guards against a regression class that wouldn't show up any other
         * way: if idempotency-key validation ever moved to AFTER the lock
         * acquire (instead of before, where it presumably is today), every
         * rejected 400 would silently leave a lock sitting in Redis for its
         * full 30s TTL. Nothing about the 400 response itself would reveal
         * that — only checking the lock key directly does.
         */
        @Test(description = "A rejected (400) request due to an invalid idempotency key never creates a lock in the cluster")
        @Story("Distributed Lock - Negative Cases (Cluster)")
        @Severity(SeverityLevel.CRITICAL)
        public void testRejectedRequestDoesNotCreateOrphanedLock() {
            logStep("TEST: Malformed idempotency key should be rejected AND never touch the lock at all");

            PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                    .registerCustomer()
                    .registerSeller()
                    .createProductWithStock(19.99, 500)
                    .execute();

            String userId = purchase.getCustomer().getUser().getId();
            String token = purchase.getCustomer().getAccessToken();
            // Same boundary pattern as OrderIdempotencyEdgeCasesTest — below the
            // 8-char minimum, guaranteed rejection.
            String malformedKey = "a".repeat(7);
            TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();

            ServiceResponse response = orderApiClient(token).createOrderWithFault(userId, malformedKey, orderRequest, null);

            assertThat(response.getStatusCode())
                    .as("Malformed idempotency key should be rejected")
                    .isEqualTo(400);

            String lockKey = lockKey(userId, malformedKey);
            assertThat(RedisValidator.keyExists(lockKey))
                    .as("A rejected request must never leave a lock behind — validation should happen " +
                            "before any Redis call, not after")
                    .isFalse();

            logStep("✅ Rejected request correctly touched no lock in the cluster");
        }

        // ══════════════════════════════════════════════════════════════
        // 6. NEGATIVE: CROSS-USER LOCK ISOLATION (asserted at the lock level)
        // ══════════════════════════════════════════════════════════════

        /**
         * testIdempotencyKeyScopedToUser (OrderIdempotencyTest) already proves
         * two users sharing the same idempotency KEY get different orders — but
         * only by inferring it from the HTTP response, same gap the whole
         * idempotency/lock split exists to close. This asserts isolation at the
         * lock level directly, and specifically under CONCURRENT load — worth
         * doing here rather than only in the single-instance suite because two
         * different full lock-key strings (userId is embedded in the key) could
         * still coincidentally land on the SAME cluster node, which is exactly
         * the kind of collision risk that can't be observed in single-instance
         * mode at all.
         */
        @Test(description = "Two different users sharing the same idempotency key string never interfere, even under concurrent load")
        @Story("Distributed Lock - Negative Cases (Cluster)")
        @Severity(SeverityLevel.CRITICAL)
        public void testCrossUserLockIsolationUnderConcurrency() throws Exception {
            logStep("TEST: Two users, same idempotency key string, fired concurrently — must not interfere");

            PurchaseResult purchase1 = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                    .registerCustomer()
                    .registerSeller()
                    .createProductWithStock(19.99, 500)
                    .execute();
            PurchaseResult purchase2 = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                    .registerCustomer()
                    .execute();

            String userId1 = purchase1.getCustomer().getUser().getId();
            String token1 = purchase1.getCustomer().getAccessToken();
            String userId2 = purchase2.getCustomer().getUser().getId();
            String token2 = purchase2.getCustomer().getAccessToken();

            // Deliberately the SAME key string for both users
            String sharedIdempotencyKey = TestDataFactory.newIdempotencyKey();
            String lockKey1 = lockKey(userId1, sharedIdempotencyKey);
            String lockKey2 = lockKey(userId2, sharedIdempotencyKey);

            logStep("  User 1 lock key: " + lockKey1 + " (slot " + RedisValidator.clusterKeySlot(lockKey1) + ")");
            logStep("  User 2 lock key: " + lockKey2 + " (slot " + RedisValidator.clusterKeySlot(lockKey2) + ")");

            TestModels.CreateOrderRequest orderRequest1 = TestDataFactory.defaultOrder(purchase1.getProducts()).build();
            TestModels.CreateOrderRequest orderRequest2 = TestDataFactory.defaultOrder(purchase1.getProducts()).build();

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(2);
            List<ServiceResponse> responses = new CopyOnWriteArrayList<>();

            executor.submit(() -> {
                try {
                    startGate.await();
                    responses.add(orderApiClient(token1).createOrderWithFault(userId1, sharedIdempotencyKey, orderRequest1, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
            executor.submit(() -> {
                try {
                    startGate.await();
                    responses.add(orderApiClient(token2).createOrderWithFault(userId2, sharedIdempotencyKey, orderRequest2, null));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });

            startGate.countDown();
            endGate.await();
            executor.shutdown();

            assertThat(responses).as("Both requests should succeed independently").hasSize(2);
            for (ServiceResponse response : responses) {
                assertThat(response.getStatusCode())
                        .as("Neither user's request should be treated as a duplicate of the other's")
                        .isEqualTo(201);
            }

            List<String> orderIds = new ArrayList<>();
            for (ServiceResponse response : responses) {
                orderIds.add(response.as(TestModels.OrderResponse.class).getId());
            }
            assertThat(orderIds).as("Each user should get their OWN distinct order").doesNotHaveDuplicates();

            assertThat(RedisValidator.keyExists(lockKey1))
                    .as("User 1's lock should be released after completion")
                    .isFalse();
            assertThat(RedisValidator.keyExists(lockKey2))
                    .as("User 2's lock should be released after completion")
                    .isFalse();

            logStep("✅ Both users' requests succeeded independently with no lock interference, " +
                    "regardless of hash slot placement");
        }
    }