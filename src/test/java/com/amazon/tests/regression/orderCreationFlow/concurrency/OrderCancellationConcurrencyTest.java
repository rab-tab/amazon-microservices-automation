package com.amazon.tests.regression.orderCreationFlow.concurrency;

import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.reports.ExtentReportManager;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import com.aventstack.extentreports.markuputils.MarkupHelper;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests OrderService.cancelOrder()'s optimistic-locking (@Version +
 * @Retryable on ObjectOptimisticLockingFailureException/
 * StaleObjectStateException) — a genuinely different concern from
 * idempotency/distributed-locking, hence its own package rather than living
 * under orderCreationFlow.idempotency.
 *
 * ⭐ DESIGN NOTE — this shapes what the concurrent test actually asserts:
 * cancelOrder() already has a built-in idempotent no-op for an
 * already-CANCELLED order:
 *   if (order.getStatus() == Order.OrderStatus.CANCELLED) {
 *       return mapToResponse(order); // no-op, still 200
 *   }
 * So the CORRECT outcome under a real concurrent race is NOT "one thread
 * wins, the rest fail" — it's "every concurrent caller succeeds": one
 * thread's write wins outright, every other thread hits a version conflict,
 * @Retryable re-invokes the method, the retry's fresh read sees CANCELLED
 * already, and it returns success via the no-op branch. A test that only
 * checks "at least one 200" would miss a broken retry — the real proof is
 * that NONE of the concurrent callers ever see an error.
 *
 * Uses OrderApiClient.cancelOrderRaw() (confirmed to exist, returns a raw
 * ServiceResponse with no baked-in status assertion) for the concurrent
 * test, so failures show up as explicit status codes rather than caught
 * exceptions — same raw-variant-over-hard-assert pattern used elsewhere in
 * this suite (createOrderWithFault() vs createOrder()).
 */
@Slf4j
@Epic("Order Service")
@Feature("Order Cancellation - Concurrency")
public class OrderCancellationConcurrencyTest extends BaseTest {

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    // ══════════════════════════════════════════════════════════════
    // 1. BASELINE — sequential idempotent no-op, no concurrency infra
    //    needed. Establishes the no-op branch itself works before
    //    stressing it under a real race.
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Cancelling an already-cancelled order is a no-op, not an error")
    @Story("Order Cancellation - Idempotent No-op")
    @Severity(SeverityLevel.NORMAL)
    public void testCancelAlreadyCancelledOrder_IsIdempotent() {
        logStep("TEST: Sequential double-cancel should succeed both times, same final state");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient client = orderApiClient(token);

        // ⭐ Uses the "TIMEOUT" test scenario — PaymentService.handleTestScenario()
        // deliberately creates no Payment record and publishes no payment.result
        // for it, so handlePaymentResult() never fires for this order and it
        // stays PENDING for the test's entire lifetime. Without this, the
        // background payment saga (which every order automatically triggers)
        // can race the test and flip status to CONFIRMED or PAYMENT_FAILED
        // before/during the cancel calls — confirmed happening in practice.
        TestModels.OrderResponse order = client.createOrderWithTestScenario(
                userId, "cancel-idem-" + System.nanoTime(), purchase.getProducts(), "TIMEOUT");
        logStep("  ✓ Order created: " + order.getId());

        client.cancelOrder(token, userId, order.getId());
        TestModels.OrderResponse afterFirst = client.getOrder(token, userId, order.getId());
        assertThat(afterFirst.getStatus()).isEqualTo("CANCELLED");
        logStep("  ✓ First cancel succeeded — status: " + afterFirst.getStatus());

        // Second cancel on an already-cancelled order should hit the no-op
        // branch — succeed, not throw.
        client.cancelOrder(token, userId, order.getId());
        TestModels.OrderResponse afterSecond = client.getOrder(token, userId, order.getId());
        assertThat(afterSecond.getStatus())
                .as("Second cancel should be a no-op, order stays CANCELLED — not an error")
                .isEqualTo("CANCELLED");

        logStep("✅ Double-cancel handled idempotently");
    }

    // ══════════════════════════════════════════════════════════════
    // 2. THE REAL TEST — concurrent cancels racing on @Version
    // ══════════════════════════════════════════════════════════════

    @Test(description = "N concurrent cancel requests on the same order ALL succeed — proves @Retryable resolves the optimistic-lock conflict rather than surfacing an error to any caller")
    @Story("Order Cancellation - Optimistic Locking Under Concurrency")
    @Severity(SeverityLevel.CRITICAL)
    public void testConcurrentCancelRequests_OptimisticLockingResolvesGracefully() throws Exception {
        logStep("TEST: Concurrent cancels on the same order — every caller must succeed");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient client = orderApiClient(token);

        // ⭐ Same isolation as the baseline test above — TIMEOUT scenario keeps
        // this order PENDING for the test's entire duration, so the 5
        // concurrent threads are racing ONLY each other on a stable PENDING
        // order, not also racing an unpredictable async payment saga. Without
        // this, a run can fail for a completely different reason (order
        // already PAYMENT_FAILED/CONFIRMED by the time threads fire) that has
        // nothing to do with whether optimistic-locking retry actually works
        // — confirmed happening in practice (all 5 threads got 400
        // "PAYMENT_FAILED" on one run, before the race even started).
        TestModels.OrderResponse order = client.createOrderWithTestScenario(
                userId, "cancel-race-" + System.nanoTime(), purchase.getProducts(), "TIMEOUT");
        logStep("  ✓ Order created: " + order.getId());

        int concurrentCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrentCount);
        List<ServiceResponse> responses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrentCount; i++) {
            final int requestNum = i + 1;
            executor.submit(withTestContext(() -> {
                try {
                    startGate.await();
                    ServiceResponse response = client.cancelOrderRaw(token, userId, order.getId());
                    responses.add(response);
                    log.info("✓ Thread {} cancel call returned status {}", requestNum, response.getStatusCode());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            }));
        }

        logStep("  🏁 Releasing all " + concurrentCount + " concurrent cancel requests...");
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        assertThat(responses).as("All " + concurrentCount + " requests should get a response").hasSize(concurrentCount);

        long errorCount = responses.stream().filter(r -> r.getStatusCode() >= 400).count();
        if (errorCount > 0) {
            responses.stream().filter(r -> r.getStatusCode() >= 400)
                    .forEach(r -> {
                        log.error("   Status {}: {}", r.getStatusCode(), r.getBody());
                        ExtentReportManager.getInstance().getTest().fail(
                                MarkupHelper.createCodeBlock("Status " + r.getStatusCode() + "\n" + r.getBody()));
                    });
        }
        assertThat(errorCount)
                .as("Every concurrent cancel should succeed (200) — a broken/absent retry would surface " +
                        "ObjectOptimisticLockingFailureException/StaleObjectStateException (or an exhausted-retry " +
                        "error) as a 4xx/5xx to at least one caller instead of resolving via the idempotent " +
                        "no-op branch")
                .isZero();

        long successCount = responses.stream().filter(r -> r.getStatusCode() == 200).count();
        assertThat(successCount)
                .as("All " + concurrentCount + " concurrent cancels should return 200")
                .isEqualTo((long) concurrentCount);

        TestModels.OrderResponse finalState = client.getOrder(token, userId, order.getId());
        assertThat(finalState.getStatus())
                .as("Order should end up CANCELLED exactly once, regardless of how many concurrent " +
                        "requests raced to cancel it")
                .isEqualTo("CANCELLED");

        logStep("✅ All " + concurrentCount + " concurrent cancels succeeded (200), order correctly CANCELLED — " +
                "optimistic locking + retry resolved the race with no errors surfaced to any caller");
    }
}
