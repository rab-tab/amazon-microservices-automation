package com.amazon.tests.regression.e2e;

import com.amazon.tests.BaseTest;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.annotations.Test;

@Epic("Amazon Microservices")
@Feature("End-to-End Purchase Flow")
public class E2EPurchaseFlowTest extends BaseTest {


    private final PurchaseValidator purchaseValidator=new PurchaseValidator();


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
