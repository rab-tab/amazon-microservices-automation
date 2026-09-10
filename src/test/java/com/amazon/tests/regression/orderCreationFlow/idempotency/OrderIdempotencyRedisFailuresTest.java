package com.amazon.tests.regression.orderCreationFlow.idempotency;



import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.RedisValidator;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.*;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis Network & Connectivity Failures - Realistic Tests
 *
 * Strategy: a PERSISTENT, natively-installed Toxiproxy instance
 * (toxiproxy-server, no Docker) sits permanently between order-service and
 * local Redis. order-service's local profile is configured to always route
 * through the proxy (127.0.0.1:8666) instead of talking to Redis directly
 * (127.0.0.1:6379) — see scripts/toxiproxy/README.md for one-time setup.
 *
 * This replaces the earlier Testcontainers-based ephemeral-proxy approach,
 * which required manually restarting order-service pointed at a freshly
 * created proxy before every run. With the persistent setup, order-service
 * is ALWAYS wired through Toxiproxy, so there is no manual precondition —
 * this class just connects to the already-running proxy's admin API and
 * injects/removes toxics per test.
 *
 * WHAT THIS VERIFIES: OrderIdempotencyService.checkAndAcquire() is
 * fail-open — Redis being unreachable/slow/reset should degrade order
 * creation to DB-only idempotency (slower, no lock-based race
 * avoidance) rather than failing the request outright.
 *
 * PREREQUISITE (one-time, not per-run): toxiproxy-server must be running
 * locally with scripts/toxiproxy/toxiproxy.json loaded, and order-service's
 * local profile must point spring.data.redis.port at 8666. See
 * scripts/toxiproxy/README.md. If those aren't set up, @BeforeSuite below
 * fails fast with a clear message rather than tests failing for the wrong
 * reason.
 *
 * Run frequency: Before releases (not part of standard regression).
 */
@Slf4j
@Epic("Order Service")
@Feature("Redis Network Failures (Realistic)")
public class OrderIdempotencyRedisFailuresTest extends BaseTest {

    private static final String TOXIPROXY_ADMIN_HOST = "127.0.0.1";
    private static final int TOXIPROXY_ADMIN_PORT = 8474; // toxiproxy-server default
    private static final String REDIS_PROXY_NAME = "redis";

    private static Proxy redisProxy;

    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    // ══════════════════════════════════════════════════════════════
    // CONNECT TO THE ALREADY-RUNNING PROXY (no container lifecycle)
    // ══════════════════════════════════════════════════════════════

    @BeforeSuite
    public static void connectToProxy() {
        log.info("🔌 Connecting to persistent Toxiproxy admin API at {}:{}...",
                TOXIPROXY_ADMIN_HOST, TOXIPROXY_ADMIN_PORT);

        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(TOXIPROXY_ADMIN_HOST, TOXIPROXY_ADMIN_PORT);

        try {
            redisProxy = toxiproxyClient.getProxy(REDIS_PROXY_NAME);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not reach Toxiproxy at " + TOXIPROXY_ADMIN_HOST + ":" + TOXIPROXY_ADMIN_PORT +
                            ", or the '" + REDIS_PROXY_NAME + "' proxy isn't defined. " +
                            "Make sure toxiproxy-server is running with scripts/toxiproxy/toxiproxy.json loaded " +
                            "(see scripts/toxiproxy/README.md) before running this suite.", e);
        }

        if (redisProxy == null) {
            throw new IllegalStateException(
                    "Toxiproxy admin API reachable, but no proxy named '" + REDIS_PROXY_NAME + "' exists. " +
                            "Check scripts/toxiproxy/toxiproxy.json was loaded on toxiproxy-server startup.");
        }

        log.info("✅ Connected to '{}' proxy — confirm order-service's local profile points " +
                "spring.data.redis.port at the proxy port (see scripts/toxiproxy/README.md), " +
                "not directly at Redis, or injected chaos will have no effect.", REDIS_PROXY_NAME);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SETUP
    // ══════════════════════════════════════════════════════════════

    @BeforeMethod
    public void setup() {
        purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(29.99, 500)
                .execute();

        orderApiClient = new OrderApiClient(
                new BearerAuthStrategy(purchase.getCustomer().getAccessToken()),
                context.getExecutor());
    }

    @AfterMethod
    public void cleanupToxics() {
        if (redisProxy != null) {
            try {
                redisProxy.toxics().getAll().forEach(toxic -> {
                    try {
                        toxic.remove();
                        logStep("  🧹 Removed toxic: " + toxic.getName());
                    } catch (IOException e) {
                        log.warn("Failed to remove toxic: {}", toxic.getName(), e);
                    }
                });
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            logStep("🧹 Redis proxy cleaned up - connection restored (proxy itself stays running)");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TESTS
    // ══════════════════════════════════════════════════════════════

    @Test(description = "REALISTIC: Redis connection cut — order creation still succeeds via DB-only fallback")
    @Story("Redis Fail-Open Behavior")
    @Severity(SeverityLevel.CRITICAL)
    public void test01_RedisConnectionCut_OrderCreationStillSucceeds() throws IOException {
        logStep("REALISTIC TEST: Redis TCP connection cut");

        redisProxy.toxics().bandwidth("cut_connection", ToxicDirection.DOWNSTREAM, 0);
        logStep("  ✂️  Redis connection CUT (via bandwidth toxic with 0 rate)");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                purchase.getCustomer().getUser().getId(), idempotencyKey, purchase.getProducts());

        assertThat(order.getId()).as("Order should still be created via DB-only fallback despite Redis being down").isNotNull();
        assertThat(order.getStatus()).isEqualTo("PENDING");

        logStep("✅ Order created successfully despite Redis being unreachable — fail-open behavior confirmed");
    }

    @Test(description = "REALISTIC: Redis latency does not block order creation indefinitely")
    @Story("Redis Fail-Open Behavior")
    @Severity(SeverityLevel.CRITICAL)
    public void test02_RedisLatency_OrderCreationDoesNotHang() {
        logStep("REALISTIC TEST: 5s Redis latency should not cause an indefinite hang");

        try {
            redisProxy.toxics().latency("high_latency", ToxicDirection.UPSTREAM, 5000);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logStep("  🐌 5s latency injected on Redis connection");

        long start = System.currentTimeMillis();
        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                purchase.getCustomer().getUser().getId(), idempotencyKey, purchase.getProducts());
        long duration = System.currentTimeMillis() - start;

        logStep("  Duration: " + duration + "ms");

        assertThat(order.getId()).as("Order should still be created despite slow Redis").isNotNull();
        assertThat(duration)
                .as("Order creation should be bounded by a Redis client timeout, not hang for the full injected latency plus DB work indefinitely")
                .isLessThan(15000L);

        logStep("✅ Order created within a bounded time despite Redis latency");
    }

    @Test(description = "REALISTIC: Redis connection reset — fallback still resolves correctly")
    @Story("Redis Fail-Open Behavior")
    @Severity(SeverityLevel.CRITICAL)
    public void test03_RedisConnectionReset_FallbackResolvesCorrectly() {
        logStep("REALISTIC TEST: Redis connection reset by peer");

        try {
            redisProxy.toxics().resetPeer("reset_connection", ToxicDirection.DOWNSTREAM, 500);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logStep("  🔌 Redis connection reset toxic injected");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        TestModels.OrderResponse order = orderApiClient.createOrder(
                purchase.getCustomer().getUser().getId(), idempotencyKey, purchase.getProducts());

        assertThat(order.getId()).isNotNull();

        logStep("✅ Order created successfully despite Redis connection resets");
    }

    @Test(description = "REALISTIC: Idempotency still holds when Redis is unreachable (DB-only dedup)")
    @Story("Redis Fail-Open Behavior")
    @Severity(SeverityLevel.CRITICAL)
    public void test04_IdempotencyHoldsWithRedisDown() {
        logStep("REALISTIC TEST: Duplicate requests still dedup correctly via DB when Redis is down");

        try {
            redisProxy.toxics().resetPeer("cut_connection", ToxicDirection.DOWNSTREAM, 0);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        logStep("  ✂️  Redis connection CUT");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        String userId = purchase.getCustomer().getUser().getId();

        TestModels.OrderResponse first = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        logStep("  ✓ First request created order: " + first.getId());

        // NOTE: deliberately NOT using createOrder() here — it hard-asserts 201,
        // but a duplicate idempotency-key request correctly returns 200. Using
        // createOrderWithFault() (no baked-in status assertion) so we can check
        // the status explicitly, same pattern as OrderIdempotencyTest.
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();
        ServiceResponse duplicateResponse = orderApiClient.createOrderWithFault(userId, idempotencyKey, orderRequest, null);

        assertThat(duplicateResponse.getStatusCode())
                .as("Duplicate request against a DB-only fallback (Redis down) should return 200, not create a new order")
                .isEqualTo(200);

        TestModels.OrderResponse duplicate = duplicateResponse.as(TestModels.OrderResponse.class);
        assertThat(duplicate.getId())
                .as("Duplicate request should return the SAME order even with Redis fully down — proves DB unique constraint + fallback lookup work without the lock")
                .isEqualTo(first.getId());

        logStep("✅ Idempotency correctly enforced via DB fallback with Redis unreachable");
    }

    @Test(description = "REALISTIC: Cache is rebuilt once Redis recovers after an outage")
    @Story("Redis Fail-Open Behavior")
    @Severity(SeverityLevel.NORMAL)
    public void test05_CacheRebuildsAfterRedisRecovers() throws Exception {
        logStep("REALISTIC TEST: Cache rebuilds once Redis comes back after an outage");

        String idempotencyKey = TestDataFactory.newIdempotencyKey();
        String userId = purchase.getCustomer().getUser().getId();

        redisProxy.toxics().resetPeer("cut_connection", ToxicDirection.DOWNSTREAM, 0);
        logStep("  ✂️  Redis connection CUT");

        TestModels.OrderResponse order = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
        logStep("  ✓ Order created via DB-only path (Redis down): " + order.getId());

        // Restore Redis connectivity
        redisProxy.toxics().get("cut_connection").remove();
        logStep("  🟢 Redis connection restored");
        Thread.sleep(2000);
        logStep("  ⏳ Waited 500ms for Redis client reconnection before verifying recovery");


        // A subsequent duplicate request, now that Redis is healthy again, should
        // rebuild the cache — verify the DB-derived record is still correct and
        // that Redis eventually reflects it.
        // NOTE: same fix as test04 — createOrder() hard-asserts 201, but this is
        // deliberately a duplicate-key request that correctly returns 200.
        TestModels.CreateOrderRequest orderRequest = TestDataFactory.defaultOrder(purchase.getProducts()).build();
        ServiceResponse duplicateResponse = orderApiClient.createOrderWithFault(userId, idempotencyKey, orderRequest, null);

        assertThat(duplicateResponse.getStatusCode())
                .as("Duplicate request after Redis recovery should return 200, not create a new order")
                .isEqualTo(200);

        TestModels.OrderResponse duplicate = duplicateResponse.as(TestModels.OrderResponse.class);
        assertThat(duplicate.getId()).isEqualTo(order.getId());

        String cacheKey = "idempotency:order:" + userId + ":" + idempotencyKey;
        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(RedisValidator.keyExists(cacheKey))
                        .as("Cache should be rebuilt once Redis is healthy again and a duplicate request is processed")
                        .isTrue());

        logStep("✅ Cache rebuilt correctly after Redis recovery");
    }
}