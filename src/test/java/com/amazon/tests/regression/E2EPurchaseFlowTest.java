package com.amazon.tests.regression;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.AuthFacade;
import com.amazon.tests.utils.facade.OrderFacade;
import com.amazon.tests.utils.facade.PaymentFacade;
import com.amazon.tests.utils.facade.ProductFacade;
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

        PurchaseResult purchase = PurchaseWorkflow.start()

                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct()
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

        PurchaseResult purchase = PurchaseWorkflow.start()

                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct()
                .createOrder()
                .cancelOrder()
                .execute();

        purchaseValidator.verifyOrderCancelled(purchase);

        logStep("✅ Order Cancellation Flow completed successfully!");
    }

    @Test
    @Story("Complete Purchase Flow")
    @Severity(SeverityLevel.BLOCKER)
    @Description("E2E test: Register → Login → Browse Products → Create Order → Verify Saga")
    public void testCompletePurchaseFlowOldLogic() {
        AuthFacade authFacade=new AuthFacade();
        ProductFacade productFacade=new ProductFacade();
        OrderFacade orderFacade=new OrderFacade();
        PaymentFacade paymentFacade=new PaymentFacade();
        TestModels.RegisterRequest customerData;
        TestModels.AuthResponse sellerData;
        TestModels.ProductResponse productData;
        TestModels.OrderResponse orderData;

        // ─── STEP 1: Register new customer ─────────────────────────────
        logStep("STEP 1: Registering new customer");
        customerData=authFacade.registerCustomer();
        logStep("STEP 2: Registerd new customer");

        // ─── STEP 2: Login ─────────────────────────────────────────────
        logStep("STEP 3: Customer login");
        authFacade.login(customerData.getEmail(),customerData.getPassword());
        logStep("STEP 4: Login successful");

        // ─── STEP 3: Register a seller and create product ──────────────
        logStep("STEP 5: Register seller ");
        sellerData=authFacade.registerSeller();
        logStep("STEP 6: Create product");

        productData=productFacade.createProduct(sellerData);


        // ─── STEP 7: Customer views product ────────────────────────────
        logStep("STEP 7: Customer views product details");
        productFacade.getProduct(productData.getId());


        // ─── STEP 8: Customer browses product catalog ──────────────────
        logStep("STEP 8: Customer browses product listing");
        productFacade.browseProducts();

        // ─── STEP 9: Customer creates order ────────────────────────────
        logStep("STEP 9: Customer places order");
        orderData=orderFacade.createOrder(productData.getId(),productData.getName(),productData.getPrice(),
                AuthFacade.customerId,AuthFacade.customerToken);

        logStep("STEP 10: Order created");

        // ─── STEP 11: Verify order is in customer's order history ───────
        logStep("STEP 11: Verifying order in customer's order history");
        orderFacade.getOrder(AuthFacade.customerId,AuthFacade.customerToken,orderData.getId());


        // ─── STEP 12: Verify payment was processed (Saga) ───────────────
        logStep("STEP 12: Checking payment status (Kafka Saga)");
        // Allow time for Kafka saga to process
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        //paymentFacade.processPayment(orderData.getId());


        // ─── STEP 13: Verify order status updated after payment ─────────
        logStep("STEP 13: Verifying order status updated by payment saga");
        orderFacade.verofyOrderStatus(orderData.getId(),AuthFacade.customerToken);


        logStep("✅ E2E Purchase Flow completed successfully!");
    }
}
