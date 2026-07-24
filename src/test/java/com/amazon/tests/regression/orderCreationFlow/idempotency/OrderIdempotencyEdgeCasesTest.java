package com.amazon.tests.regression.orderCreationFlow.idempotency;



import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotency edge cases beyond the core duplicate-detection scenarios
 * already covered by OrderIdempotencyTest / OrderIdempotencyEventualConsistencyTest:
 *
 * - Payload mismatch on a reused key (documents current behavior)
 * - Idempotency key format boundary validation
 * - Cross-endpoint key namespace isolation
 * - Concurrent requests with DIFFERENT keys (no accidental interference)
 *
 * Fast, no additional infra required — every-commit cadence.
 */
@Slf4j
@Epic("Order Service")
@Feature("Idempotency - Edge Cases")
public class OrderIdempotencyEdgeCasesTest extends BaseTest {

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // 1. SAME KEY, DIFFERENT PAYLOAD
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Same idempotency key with a DIFFERENT order payload — documents actual behavior")
    @Story("Idempotency - Payload Mismatch")
    @Severity(SeverityLevel.NORMAL)
    public void testSameKeyDifferentPayload_DocumentsBehavior() {
        logStep("TEST: Same idempotency key reused with a different order payload");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .createProductWithStock(49.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient orderApiClient = orderApiClient(token);
        String idempotencyKey = TestDataFactory.newIdempotencyKey();

        TestModels.ProductResponse productA = purchase.getProducts().get(0);
        TestModels.ProductResponse productB = purchase.getProducts().get(1);

        TestModels.OrderResponse firstOrder = orderApiClient.createOrder(userId, idempotencyKey, List.of(productA));
        logStep("  ✓ First order created: " + firstOrder.getId() + " | total: " + firstOrder.getTotalAmount());

        TestModels.CreateOrderRequest differentPayload =
                TestDataFactory.defaultOrder(List.of(productB)).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(userId, idempotencyKey, differentPayload, null);

        logStep("  Response status: " + response.getStatusCode());
        logStep("  Response body: " + response.getBody());

        if (response.getStatusCode() == 200) {
            TestModels.OrderResponse returned = response.as(TestModels.OrderResponse.class);
            assertThat(returned.getId())
                    .as("If 200, should return the ORIGINAL order, ignoring the new payload")
                    .isEqualTo(firstOrder.getId());
            logStep("  ℹ️ BEHAVIOR: system returns original order regardless of payload mismatch (200)");
        } else if (response.getStatusCode() == 409 || response.getStatusCode() == 400) {
            logStep("  ℹ️ BEHAVIOR: system rejects mismatched payload for a reused key (status "
                    + response.getStatusCode() + ")");
        } else {
            throw new AssertionError("Unexpected status for reused key with different payload: "
                    + response.getStatusCode() + " — body: " + response.getBody());
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 2. KEY FORMAT BOUNDARY VALIDATION
    // ══════════════════════════════════════════════════════════════

    @DataProvider(name = "idempotencyKeyBoundaries")
    public Object[][] idempotencyKeyBoundaries() {
        return new Object[][] {
                { "7 chars (below min of 8)", "a".repeat(7), false },
                { "8 chars (exact min)", "a".repeat(8), true },
                { "256 chars (exact max)", "a".repeat(256), true },
                { "257 chars (above max)", "a".repeat(257), false },
                { "contains space", "abcd efgh", false },
                { "contains special char", "abcd@efgh", false },
                { "contains unicode", "abcdéfgh", false },
                { "valid with hyphens", "abc-123-def-456", true }
        };
    }

    @Test(dataProvider = "idempotencyKeyBoundaries")
    @Story("Idempotency - Key Format Validation")
    @Severity(SeverityLevel.NORMAL)
    public void testIdempotencyKeyFormatBoundaries(String scenario, String key, boolean shouldBeAccepted) {
        logStep("TEST: " + scenario + " — key length " + key.length());

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient orderApiClient = orderApiClient(token);

        TestModels.CreateOrderRequest orderRequest =
                TestDataFactory.defaultOrder(purchase.getProducts()).build();

        ServiceResponse response = orderApiClient.createOrderWithFault(userId, key, orderRequest, null);

        logStep("  Response status: " + response.getStatusCode());

        if (shouldBeAccepted) {
            assertThat(response.getStatusCode()).as(scenario + " should be accepted").isEqualTo(201);
        } else {
            assertThat(response.getStatusCode()).as(scenario + " should be rejected").isEqualTo(400);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 3. CROSS-ENDPOINT KEY NAMESPACE ISOLATION
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Same idempotency key value used for order creation should not collide with cancellation")
    @Story("Idempotency - Cross-Endpoint Isolation")
    @Severity(SeverityLevel.NORMAL)
    public void testIdempotencyKeyIsolatedAcrossEndpoints() {
        logStep("TEST: Idempotency key namespace isolation between create and cancel");
        logStep("NOTE: cancelOrder does not currently accept its own idempotency key — "
                + "this documents today's behavior and should be extended once that support is added "
                + "(tracked as an open gap alongside cancelOrder's missing TOCTOU protection).");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient orderApiClient = orderApiClient(token);

        String sharedKeyValue = TestDataFactory.newIdempotencyKey();

        TestModels.OrderResponse order = orderApiClient.createOrder(userId, sharedKeyValue, purchase.getProducts());
        logStep("  ✓ Order created with key: " + sharedKeyValue);

        orderApiClient.cancelOrder(token, userId, order.getId());
        logStep("  ✓ Order cancelled — no interference with the create-flow's use of the same key value observed");

        TestModels.OrderResponse verify = orderApiClient.getOrder(token, userId, order.getId());
        assertThat(verify.getStatus()).isEqualTo("CANCELLED");
    }

    // ══════════════════════════════════════════════════════════════
    // 4. CONCURRENT REQUESTS, DIFFERENT KEYS
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Concurrent requests with DIFFERENT idempotency keys create independent orders, no cross-interference")
    @Story("Idempotency - Concurrent Independent Keys")
    @Severity(SeverityLevel.NORMAL)
    public void testConcurrentDifferentKeys_NoInterference() throws InterruptedException {
        logStep("TEST: Concurrent requests with different idempotency keys don't interfere");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient orderApiClient = orderApiClient(token);

        int concurrentCount = 5;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < concurrentCount; i++) keys.add(TestDataFactory.newIdempotencyKey());

        List<TestModels.OrderResponse> results = new CopyOnWriteArrayList<>();
        List<Thread> threads = new ArrayList<>();

        for (String key : keys) {
            Thread t = new Thread(() -> results.add(orderApiClient.createOrder(userId, key, purchase.getProducts())));
            threads.add(t);
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) t.join();

        Set<String> orderIds = results.stream().map(TestModels.OrderResponse::getId).collect(Collectors.toSet());

        assertThat(results).as("All " + concurrentCount + " concurrent requests should succeed").hasSize(concurrentCount);
        assertThat(orderIds)
                .as("Each distinct idempotency key should produce its OWN distinct order — no accidental collision under concurrency")
                .hasSize(concurrentCount);

        logStep("✅ " + concurrentCount + " concurrent requests with different keys created " + orderIds.size() + " independent orders");
    }
}