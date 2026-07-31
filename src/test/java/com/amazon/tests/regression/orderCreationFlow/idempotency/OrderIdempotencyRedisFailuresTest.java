package com.amazon.tests.regression.orderCreationFlow.idempotency;



import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
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
import org.testcontainers.containers.ToxiproxyContainer;
import org.testng.annotations.*;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Redis Network & Connectivity Failures - Realistic Tests
 *
 * Strategy: Toxiproxy in front of the REAL local Redis instance (not a
 * disposable Testcontainers Redis) — keeps resource footprint minimal
 * and exercises the actual fail-open fallback logic in
 * OrderIdempotencyService against the Redis client the service really uses.
 *
 * ⚠️ MANUAL PRECONDITION: order-service MUST be started with its Redis
 * connection pointed at the Toxiproxy endpoint logged at suite startup
 * (SPRING_DATA_REDIS_HOST / SPRING_DATA_REDIS_PORT). Unlike a
 * disconnected/isolated container, this test needs the service to
 * genuinely route through the proxy for the injected chaos to have any
 * effect on the real request path.
 *
 * WHAT THIS VERIFIES: OrderIdempotencyService.checkAndAcquire() is
 * fail-open — Redis being unreachable/slow/reset should degrade order
 * creation to DB-only idempotency (slower, no lock-based race
 * avoidance) rather than failing the request outright.
 *
 * Run frequency: Before releases (not part of standard regression —
 * requires manual service reconfiguration).
 */
@Slf4j
@Epic("Order Service")
@Feature("Redis Network Failures (Realistic)")
public class OrderIdempotencyRedisFailuresTest extends BaseTest {

    private static final String LOCAL_REDIS_HOST = "host.docker.internal"; // adjust if Redis isn't reachable this way from containers
    private static final int LOCAL_REDIS_PORT = 6379;

    private static ToxiproxyContainer toxiproxy;
    private static Proxy redisProxy;

    private PurchaseResult purchase;
    private OrderApiClient orderApiClient;

    // ══════════════════════════════════════════════════════════════
    // CONTAINER SETUP
    // ══════════════════════════════════════════════════════════════

    @BeforeSuite
    public static void setupProxy() throws IOException {
        log.info("🐳 Starting Toxiproxy container (pointing at existing local Redis)...");

        toxiproxy = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0");
        toxiproxy.start();

        ToxiproxyClient toxiproxyClient = new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());

        redisProxy = toxiproxyClient.createProxy(
                "redis",
                "0.0.0.0:8667",
                LOCAL_REDIS_HOST + ":" + LOCAL_REDIS_PORT
        );

        String proxiedEndpoint = toxiproxy.getHost() + ":" + toxiproxy.getMappedPort(8667);

        log.info("✅ Toxiproxy started, proxying to local Redis at {}:{}", LOCAL_REDIS_HOST, LOCAL_REDIS_PORT);
        log.info("   Proxied endpoint: {}", proxiedEndpoint);

        log.warn("╔══════════════════════════════════════════════════════════════════╗");
        log.warn("║  MANUAL PRECONDITION REQUIRED — READ BEFORE RUNNING THIS SUITE     ║");
        log.warn("║                                                                      ║");
        log.warn("║  order-service MUST be started with:                                ║");
        log.warn("║    SPRING_DATA_REDIS_HOST={}                          ║", toxiproxy.getHost());
        log.warn("║    SPRING_DATA_REDIS_PORT={}                                    ║", toxiproxy.getMappedPort(8667));
        log.warn("║                                                                      ║");
        log.warn("║  If the service is NOT pointed at the proxy above, every test in    ║");
        log.warn("║  this class will pass or fail for the WRONG REASON — the injected   ║");
        log.warn("║  network chaos will have zero effect on the real request path.      ║");
        log.warn("╚══════════════════════════════════════════d════════════════════════╝");
    }

    @AfterSuite
    public static void teardownProxy() {
        if (toxiproxy != null) toxiproxy.stop();
        log.info("🧹 Toxiproxy container stopped");
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
            logStep("🧹 Redis proxy cleaned up - connection restored");
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

        TestModels.OrderResponse duplicate = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());

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

        // A subsequent duplicate request, now that Redis is healthy again, should
        // rebuild the cache — verify the DB-derived record is still correct and
        // that Redis eventually reflects it.
        TestModels.OrderResponse duplicate = orderApiClient.createOrder(userId, idempotencyKey, purchase.getProducts());
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