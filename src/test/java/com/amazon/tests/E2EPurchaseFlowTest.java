package com.amazon.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.AuthUtils;
import com.amazon.tests.utils.facade.AuthFacade;
import com.amazon.tests.utils.facade.OrderFacade;
import com.amazon.tests.utils.facade.PaymentFacade;
import com.amazon.tests.utils.facade.ProductFacade;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import io.restassured.response.Response;
import org.testng.annotations.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

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
                .processPayment()
                .execute();

        logStep("Validating Purchase Workflow");
        purchaseValidator.verifyPurchaseCompleted(purchase);

        logStep("✅ E2E Purchase Flow completed successfully!");
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

    @Test
    @Story("Order Cancellation Flow")
    @Severity(SeverityLevel.CRITICAL)
    @Description("E2E test: Create order then cancel it")
    public void testOrderCancellationFlow() {
        logStep("STEP 1: Setup customer and product");
        TestModels.AuthResponse customerAuth = AuthUtils.registerAndGetAuth();
        String customerToken = customerAuth.getAccessToken();
        String customerId = customerAuth.getUser().getId();

        TestModels.AuthResponse sellerAuth = AuthUtils.registerAndGetAuth();
        Response productResp = given()
                .spec(RestAssuredConfig.getProductServiceSpec())
                .header("X-User-Id", sellerAuth.getUser().getId())
                .body(TestDataFactory.createProductWithPrice(19.99))
                .when()
                .post("/api/v1/products")
                .then()
                .statusCode(201)
                .extract().response();
        TestModels.ProductResponse product = productResp.as(TestModels.ProductResponse.class);

        logStep("STEP 2: Create order");
        Response orderResp = given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .body(TestDataFactory.createOrderRequest(
                        product.getId(), product.getName(), product.getPrice()))
                .when()
                .post("/api/v1/orders")
                .then()
                .statusCode(201)
                .extract().response();
        String orderId = orderResp.jsonPath().getString("id");

        logStep("STEP 3: Cancel order");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .header("X-User-Id", customerId)
                .pathParam("id", orderId)
                .when()
                .patch("/api/v1/orders/{id}/cancel")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));

        logStep("STEP 4: Verify cancellation");
        given()
                .spec(RestAssuredConfig.getOrderServiceSpec(customerToken))
                .pathParam("id", orderId)
                .when()
                .get("/api/v1/orders/{id}")
                .then()
                .statusCode(200)
                .body("status", equalTo("CANCELLED"));

        logStep("✅ Order Cancellation Flow completed successfully!");
    }
}
