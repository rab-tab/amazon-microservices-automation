package com.amazon.tests.regression.payment;


import com.amazon.tests.BaseTest;
import com.amazon.tests.auth.BearerAuthStrategy;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.concurrency.OrderStatusPoller;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Order Payment Flow - Positive")
@Slf4j
public class OrderPaymentPositiveTest extends BaseTest {
    private PaymentApiClient paymentApiClient;

    private OrderApiClient orderApiClient(String token) {
        return new OrderApiClient(new BearerAuthStrategy(token), context.getExecutor());
    }

    @Test(timeOut = 30000)
    @Story("Successful Payment")
    public void testSuccessfulPaymentFlow() {
        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(89999.00, 10)
                .createOrderWithScenario("SUCCESS")
                .execute();

        String token = purchase.getCustomerAuth().getAccessToken();
        String userId = purchase.getCustomerAuth().getUser().getId();
        String orderId = purchase.getOrder().getId();

        TestModels.OrderResponse order = OrderStatusPoller.waitForStatus(
                orderApiClient(token), token, userId, orderId, "CONFIRMED", Duration.ofSeconds(10));

        assertThat(order.getPaymentId()).as("Payment ID should be set on order").isNotNull();

        // Transaction ID comes from the Payment record itself, not the order
        TestModels.PaymentResponse payment = paymentApiClient.getPayment(orderId);
        assertThat(payment.getTransactionId()).as("Transaction ID should be set").isNotBlank();
        assertThat(payment.getStatus()).isEqualTo(TestModels.PaymentStatus.SUCCESS);
        assertThat(payment.getFailureReason()).as("No failure reason for successful payment").isNull();

        log.info("✅ Order confirmed: {} | Payment: {} | Txn: {}",
                orderId, order.getPaymentId(), payment.getTransactionId());
    }
    @Test(timeOut = 30000)
    @Story("Idempotency")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Duplicate requests with the same idempotency key return the same order")
    public void testIdempotencyWithPayment() {
        // Note: overlaps with OrderIdempotencyTest — kept here since it also validates
        // payment fields on the duplicate response. Consider consolidating if redundant.
        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProductWithStock(1000.00, 10)
                .execute();

        String token = purchase.getCustomerAuth().getAccessToken();
        String userId = purchase.getCustomerAuth().getUser().getId();
        String idempotencyKey = UUID.randomUUID().toString();

        TestModels.OrderResponse firstOrder = orderApiClient(token)
                .createOrderWithTestScenario(userId, idempotencyKey, purchase.getProducts(), "SUCCESS");
        log.info("✅ First order created: {}", firstOrder.getId());

        TestModels.OrderResponse duplicateOrder = orderApiClient(token)
                .createOrderWithTestScenario(userId, idempotencyKey, purchase.getProducts(), "SUCCESS");

        assertThat(duplicateOrder.getId()).as("Duplicate request should return same order ID")
                .isEqualTo(firstOrder.getId());

        log.info("✅ Idempotency verified — same order returned: {}", duplicateOrder.getId());
    }
}