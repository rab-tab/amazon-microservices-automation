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
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Epic("Amazon Microservices")
@Feature("End-to-End Purchase Flow")
public class E2EPurchaseFlowTest extends BaseTest {


    KafkaTestConsumer kafkaConsumer ;
    ProductApiClient productApiClient ;
    OrderApiClient orderApiClient ;
    PaymentApiClient paymentApiClient;
    PurchaseValidator purchaseValidator ;



    @BeforeClass
    public void setup() {
        kafkaConsumer = new KafkaTestConsumer("payment.result");
        productApiClient = new ProductApiClient(executor);
        orderApiClient = new OrderApiClient(authStrategy, executor);
        paymentApiClient = new PaymentApiClient(kafkaConsumer, executor);
        purchaseValidator = new PurchaseValidator(productApiClient, orderApiClient, paymentApiClient);
    }
    @Test(priority = 1)
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


    @Test(priority = 2,enabled = false)
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
