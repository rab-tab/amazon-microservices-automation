package com.amazon.tests.regression.orderCreationFlow.distributedLocks;

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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Distributed lock correctness tests — FUNCTIONAL.
 *
 * Separate from idempotency OUTCOME tests (OrderIdempotencyTest /
 * OrderIdempotencyRedisFailuresTest), which only ever verify the HTTP
 * response and order ID. This class asserts directly on the lock's actual
 * state in Redis (existence + TTL), since a wrong-but-lucky outcome can
 * still pass every idempotency test while the locking mechanism underneath
 * is genuinely broken.
 *
 * Everything here runs against healthy Redis and healthy DB — no forced
 * failures, no injected faults. That's the functional/chaos dividing line
 * for this pair of classes: scenarios that need something actually broken
 * to exist at all live in OrderDistributedLockChaosTest instead, even
 * though their assertions look similarly "normal."
 *
 * OrderIdempotencyService's lock key is {@code idempotency:order:{userId}:
 * {idempotencyKey}:lock} — this class reads it directly via RedisValidator,
 * the same way the rest of the suite reads the cache key, since
 * isLockHeld()/getLockRemainingTTL() live inside order-service's own JVM
 * and aren't reachable from here.
 *
 * Cadence: every-commit, same as OrderIdempotencyTest.
 */
@Slf4j
@Epic("Order Service")
@Feature("Distributed Locking")
public class OrderDistributedLockTest extends BaseTest {

    private static final int LOCK_TTL_SECONDS = 30; // must match OrderIdempotencyService.LOCK_TTL_SECONDS

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    private String lockKey(String userId, String idempotencyKey) {
        return "idempotency:order:" + userId + ":" + idempotencyKey + ":lock";
    }

    // ══════════════════════════════════════════════════════════════
    // 1. LOCK RELEASED ON SUCCESS (new-order path)
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Lock is released immediately after a successful order creation, not left dangling until TTL expiry")
    @Story("Distributed Lock - Release on Success")
    @Severity(SeverityLevel.CRITICAL)
    public void testLockReleasedOnSuccess() {
        logStep("TEST: Lock should not exist in Redis after a successful order creation");

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
                .as("Lock should be released (deleted) immediately after successful completion, " +
                        "not left to expire via its " + LOCK_TTL_SECONDS + "s TTL")
                .isFalse();

        logStep("✅ Lock correctly released on success");
    }

    // ══════════════════════════════════════════════════════════════
    // 2. LOCK RELEASED ON DUPLICATE DETECTION (separate acquire/release cycle)
    // ══════════════════════════════════════════════════════════════

    @Test(description = "The DUPLICATE-detection path also acquires and releases its own lock cleanly — separate cycle from the new-order path")
    @Story("Distributed Lock - Release on Success")
    @Severity(SeverityLevel.CRITICAL)
    public void testLockReleasedOnDuplicateDetection() {
        logStep("TEST: Lock should not exist in Redis after a duplicate request is detected and returned");

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
        assertThat(RedisValidator.keyExists(lockKey))
                .as("Sanity check: first request's own lock should already be released before the duplicate call")
                .isFalse();

        // This is a SEPARATE acquire+release cycle from the first request's —
        // checkAndAcquire() takes a fresh lock to check for duplicates under
        // protection, then releases it once the cache/DB hit is found. Nothing
        // else in the suite verifies this specific cycle cleans up correctly.
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();
        ServiceResponse duplicateResponse = orderApiClient(token).createOrderWithFault(userId, idempotencyKey, orderRequest, null);
        assertThat(duplicateResponse.getStatusCode()).isEqualTo(200);

        assertThat(RedisValidator.keyExists(lockKey))
                .as("Duplicate-detection path should release its own lock immediately, not leave it dangling until TTL expiry")
                .isFalse();

        logStep("✅ Duplicate-detection path's own lock cycle also releases cleanly");
    }

    // ══════════════════════════════════════════════════════════════
    // 3. MUTUAL EXCLUSION UNDER CONCURRENCY (best-effort observation)
    // ══════════════════════════════════════════════════════════════

    /**
     * NOTE: polling lock state from an external monitor thread while a race
     * is happening is inherently best-effort — it can't prove the lock was
     * held at every single instant (that would need instrumentation inside
     * the service itself), only that it observed the lock existing at least
     * once during the race and confirms it's released once everything
     * settles. Treat this as a smoke check layered on top of
     * testMultipleInstancesRaceCondition's outcome-based proof, not a
     * replacement for it.
     */
    @Test(description = "Lock is observed held during a concurrent same-key race, and released once all requests complete")
    @Story("Distributed Lock - Mutual Exclusion")
    @Severity(SeverityLevel.NORMAL)
    public void testMutualExclusionUnderConcurrency() throws Exception {
        logStep("TEST: Lock should be observed held during concurrent race, released after");

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

        // Monitor thread: polls lock existence while the race is in flight
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

        logStep("✅ Lock observed held during race, released after — 1 order from " + concurrentCount + " concurrent requests");
    }
}