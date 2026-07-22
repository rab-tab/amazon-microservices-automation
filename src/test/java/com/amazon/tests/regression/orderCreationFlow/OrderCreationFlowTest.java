package com.amazon.tests.regression.orderCreationFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.validators.OrderValidator;
import com.amazon.tests.validators.ProductValidator;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;

/**
 * End-to-End Order Creation Flow Tests
 * Tests complete user journey: Registration → Browse Products → Create Order
 *
 * Uses SEEDERS because we need real data in the system via API calls
 */
@Slf4j
public class OrderCreationFlowTest extends BaseTest {
    PurchaseValidator purchaseValidator=new PurchaseValidator();
    ProductValidator productValidator=new ProductValidator(new ProductApiClient(executor));
    OrderValidator orderValidator=new OrderValidator(new OrderApiClient(authStrategy,executor));

    // ==========================================
    // SCENARIO 1: Happy Path - Single User, Single Product, Single Order
    // SCENARIO 2: Multi-Item Order
    // SCENARIO 4: Specific Product Categories
    // ==========================================

    @Test(dataProvider = "orderFlowScenarios",
            description = "Parameterized order creation flow across scenario variants")
    public void testOrderCreationFlow(OrderFlowScenario scenario) {

        logStep("Executing: " + scenario.getDescription());

        PurchaseWorkflow workflow = PurchaseWorkflow.start(executor,authStrategy)   // executor from BaseTest/context
                .registerCustomer()
                .loginCustomer()
                .registerSeller();

        workflow = (scenario.getProductType() != null)
                ? workflow.createProducts(scenario.getProductCount(), scenario.getProductType())
                : workflow.createProduct(scenario.getProductCount());

        if (scenario.getAdditionalProductCount() > 0) {
            workflow = workflow.createProducts(scenario.getAdditionalProductCount(), scenario.getAdditionalProductType());
        }

        PurchaseResult purchase = workflow
                .browseProducts()
                .viewProduct()
                .createOrder()
                .execute();

        logStep("Validating: " + scenario.getDescription());

        scenario.getValidation().accept(purchase,
                new OrderFlowScenario.OrderFlowValidators(purchaseValidator, orderValidator, productValidator));

        logStep("✅ " + scenario.getDescription() + " completed successfully!");
    }

    // ==========================================
    // SCENARIO 3: Multiple Users, Multiple Orders
    // ==========================================

    @Test(description = "Multiple users each placing their own orders")
    public void testMultipleUsersOrdering() {

        logStep("Executing Multiple User Purchase Flows");

        List<PurchaseResult> purchases = new ArrayList<>();

        for (int i = 0; i < 3; i++) {

            PurchaseResult purchase = PurchaseWorkflow.start(executor,authStrategy)
                    .registerCustomer()
                    .loginCustomer()
                    .registerSeller()
                    .createProduct(3)
                    .browseProducts()
                    .createOrder()
                    .execute();

            purchases.add(purchase);
        }

        logStep("Validating Purchase Flows");

        purchases.forEach(purchaseValidator::verifyPurchaseCompleted);

        assertEquals(purchases.size(), 3,
                "Three users should have completed purchases");

        logStep("✅ Multiple User Purchase Flow completed successfully!");
    }


    @DataProvider(name = "orderFlowScenarios")
    public Object[][] orderFlowScenarios() {
        return new Object[][] {
                {
                        OrderFlowScenario.builder()
                                .description("Basic single-item order")
                                .productCount(1)
                                .validation((purchase, v) -> v.purchaseValidator().verifyPurchaseCompleted(purchase))
                                .build()
                },
                {
                        OrderFlowScenario.builder()
                                .description("Multi-item order (5 products)")
                                .productCount(5)
                                .validation((purchase, v) ->
                                        v.purchaseValidator().verifyMultiItemPurchaseCompleted(purchase, 3, 5))
                                .build()
                },
                {
                        OrderFlowScenario.builder()
                                .description("Category-specific order (cheap + expensive)")
                                .productCount(3)
                                .productType(ProductType.CHEAP)
                                .additionalProductCount(2)
                                .additionalProductType(ProductType.EXPENSIVE)
                                .validation((purchase, v) -> {
                                    v.purchaseValidator().verifyPurchaseCompleted(purchase);
                                    v.orderValidator().verifyMinimumItems(purchase.getOrder(), 2);
                                })
                                .build()
                }
        };
    }


}