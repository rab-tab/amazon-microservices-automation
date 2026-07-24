package com.amazon.tests.regression.orderCreationFlow.idempotency;


import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Manual-verification-only idempotency tests — deliberately NOT part of
 * automated CI runs (all @Test methods are enabled=false). These require
 * a human to perform an out-of-band action (restarting local Redis) at
 * the right moment and are meant to be flipped on and run individually,
 * by hand, on the rare occasion this scenario needs re-checking.
 */
@Slf4j
@Epic("Order Service")
@Feature("Idempotency - Manual Verification")
public class OrderIdempotencyManualVerificationTest extends BaseTest {

    @Test(description = "MANUAL: Redis restart mid-flow — run manually, not part of CI", enabled = false)
    @Story("Idempotency - Redis Restart")
    @Severity(SeverityLevel.NORMAL)
    public void testRedisRestart_ManualVerification() throws Exception {
        logStep("MANUAL TEST — requires manually restarting local Redis mid-run");
        logStep("1. This test will pause for 10s.");
        logStep("2. During the pause, manually run: redis-cli SHUTDOWN NOSAVE, then restart Redis.");
        logStep("3. Verify the order still completes correctly.");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .registerSeller()
                .createProductWithStock(19.99, 500)
                .execute();

        String userId = purchase.getCustomer().getUser().getId();
        String token = purchase.getCustomer().getAccessToken();
        OrderApiClient orderApiClient = new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());

        logStep("⏳ Pausing 10s — restart Redis now if verifying manually...");
        Thread.sleep(10000);

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, TestDataFactory.newIdempotencyKey(), purchase.getProducts());

        assertThat(order.getId()).isNotNull();
        logStep("✅ Order created successfully — " + order.getId());
    }
}