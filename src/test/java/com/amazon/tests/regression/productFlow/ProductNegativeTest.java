package com.amazon.tests.regression.productFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Negative-path coverage for ProductController — previously untested:
 * a different seller attempting to update/delete someone else's product
 * (the SecurityException/403 path), same gap shape as cancelOrder()'s
 * once-untested SecurityException from earlier in this project.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Product Catalog - Negative")
public class ProductNegativeTest extends BaseTest {

    @Test(description = "A different seller attempting to update someone else's product should be rejected with 403")
    @Story("Product Update - Ownership Enforcement")
    @Severity(SeverityLevel.CRITICAL)
    public void testUpdateProduct_WrongSeller_Returns403() {
        logStep("TEST: Seller B attempts to update Seller A's product");

        PurchaseResult ownerPurchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse owner = ownerPurchase.getSellerAuth();

        PurchaseResult otherPurchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse otherSeller = otherPurchase.getSellerAuth();

        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());
        TestModels.ProductResponse product = productApiClient.createProduct(owner, 19.99, 100);
        logStep("  ✓ Product created by owner: " + product.getId());

        TestModels.ProductRequest maliciousUpdate = TestModels.ProductRequest.builder()
                .price(BigDecimal.valueOf(0.01))
                .build();

        ServiceResponse response = productApiClient.updateProductRaw(otherSeller, product.getId(), maliciousUpdate);

        assertThat(response.getStatusCode())
                .as("A non-owner seller's update attempt should be rejected with 403, not silently succeed")
                .isEqualTo(403);

        TestModels.ProductResponse unchanged = productApiClient.getProduct(product.getId());
        assertThat(unchanged.getPrice())
                .as("The product's price should be completely unaffected by the rejected update")
                .isEqualByComparingTo(BigDecimal.valueOf(19.99));

        logStep("✅ Wrong-seller update correctly rejected with 403, product unchanged");
    }

    @Test(description = "A different seller attempting to delete someone else's product should be rejected with 403")
    @Story("Product Deletion - Ownership Enforcement")
    @Severity(SeverityLevel.CRITICAL)
    public void testDeleteProduct_WrongSeller_Returns403() {
        logStep("TEST: Seller B attempts to delete Seller A's product");

        PurchaseResult ownerPurchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse owner = ownerPurchase.getSellerAuth();

        PurchaseResult otherPurchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse otherSeller = otherPurchase.getSellerAuth();

        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());
        TestModels.ProductResponse product = productApiClient.createProduct(owner, 19.99, 100);
        logStep("  ✓ Product created by owner: " + product.getId());

        ServiceResponse response = productApiClient.deleteProductRaw(otherSeller, product.getId());

        assertThat(response.getStatusCode())
                .as("A non-owner seller's delete attempt should be rejected with 403, not silently succeed")
                .isEqualTo(403);

        TestModels.ProductResponse stillActive = productApiClient.getProduct(product.getId());
        assertThat(stillActive.getStatus())
                .as("The product should remain ACTIVE — the rejected delete must not have taken effect")
                .isEqualTo("ACTIVE");

        logStep("✅ Wrong-seller delete correctly rejected with 403, product still ACTIVE");
    }
}