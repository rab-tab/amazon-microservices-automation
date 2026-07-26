package com.amazon.tests.regression.e2e;

import com.amazon.tests.BaseTest;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.annotations.Test;

@Epic("Amazon Microservices")
@Feature("End-to-End Purchase Flow")
public class E2EPurchaseFlowTest extends BaseTest {


    KafkaTestConsumer kafkaConsumer = new KafkaTestConsumer("payment.result");
    ProductApiClient productApiClient = new ProductApiClient(executor);
    OrderApiClient orderApiClient = new OrderApiClient(authStrategy, executor);
    PaymentApiClient paymentApiClient = new PaymentApiClient(kafkaConsumer, executor); // see note below

    PurchaseValidator purchaseValidator = new PurchaseValidator(productApiClient, orderApiClient, paymentApiClient);


    @Test
    @Story("Complete Purchase Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("E2E test: Register → Login → Browse Products → Create Order → Verify Saga")
    public void testCompletePurchaseFlow() {

        logStep("Executing Purchase Workflow");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)

                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct(1)
                .viewProduct()
                .browseProducts()
                .createOrder()
                .execute();

        logStep("Validating Purchase Workflow");
        purchaseValidator.verifyPurchaseCompleted(purchase);

        logStep("✅ E2E Purchase Flow completed successfully!");
    }


    @Test
    @Story("Order Cancellation Flow")
    @Severity(SeverityLevel.CRITICAL)
    @Description("E2E test: Create order then cancel it")
    public void testOrderCancellationFlow() {

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)

                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct(1)
                .createOrder()
                .cancelOrder()
                .execute();

        purchaseValidator.verifyOrderCancelled(purchase);

        logStep("✅ Order Cancellation Flow completed successfully!");
    }


}
