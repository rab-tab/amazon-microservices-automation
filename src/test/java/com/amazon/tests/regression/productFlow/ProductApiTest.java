package com.amazon.tests.regression.productFlow;


import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Product Catalog - Positive")
public class ProductApiTest extends BaseTest {

    private TestModels.AuthResponse sellerAuth;
    private TestModels.ProductRequest productRequest;
    private TestModels.ProductResponse createdProduct;
    private ProductApiClient productApiClient;

    @BeforeClass
    public void setup() {
        logStep("Setting up seller account for product tests");

        PurchaseResult result = PurchaseWorkflow.start(executor,authStrategy)
                .registerSeller()
                .execute();

        sellerAuth = result.getSellerAuth();
        productApiClient = new ProductApiClient(executor);
        productRequest = TestDataFactory.createRandomProduct();
    }

    @Test(priority = 1)
    @Story("Create Product")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify seller can create a new product")
    public void testCreateProduct() {
        logStep("Creating product: " + productRequest.getName());

        createdProduct = productApiClient.createProductRaw(sellerAuth, productRequest)
                .as(TestModels.ProductResponse.class);

        assertThat(createdProduct.getId()).isNotBlank();
        assertThat(createdProduct.getName()).isEqualTo(productRequest.getName());
        assertThat(createdProduct.getPrice()).isEqualByComparingTo(productRequest.getPrice());
        assertThat(createdProduct.getStockQuantity()).isEqualTo(productRequest.getStockQuantity());
        assertThat(createdProduct.getStatus()).isEqualTo("ACTIVE");
        assertThat(createdProduct.getRating()).isNotNull();
        assertThat(createdProduct.getReviewCount()).isEqualTo(0);
        assertThat(createdProduct.getSellerId()).isEqualTo(sellerAuth.getUser().getId());

        logStep("Product created with ID: " + createdProduct.getId());
    }

    @Test(priority = 4, dependsOnMethods = "testCreateProduct")
    @Story("Get Product")
    @Severity(SeverityLevel.BLOCKER)
    @Description("Verify product can be retrieved by ID")
    public void testGetProductById() {
        logStep("Fetching product: " + createdProduct.getId());

        TestModels.ProductResponse fetched = productApiClient.getProduct(createdProduct.getId());

        assertThat(fetched.getId()).isEqualTo(createdProduct.getId());
        assertThat(fetched.getName()).isEqualTo(productRequest.getName());
        assertThat(fetched.getStatus()).isEqualTo("ACTIVE");
    }

    @Test(priority = 6, dependsOnMethods = "testCreateProduct")
    @Story("List Products")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify product listing returns paginated results")
    public void testGetAllProducts() {
        var response = productApiClient.getAllProducts(0, 10);
        var body = response.as(java.util.Map.class);

        assertThat(body.get("products")).isNotNull();
        assertThat(body.get("page")).isEqualTo(0);
        assertThat(body.get("size")).isEqualTo(10);
    }

    @Test(priority = 7, dependsOnMethods = "testCreateProduct")
    @Story("Search Products")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify product search returns relevant results")
    public void testSearchProducts() {
        String searchQuery = productRequest.getName().split(" ")[0];

        var response = productApiClient.searchProducts(searchQuery);
        var body = response.as(java.util.Map.class);

        assertThat(body.get("products")).isNotNull();
    }

    @Test(priority = 8, dependsOnMethods = "testCreateProduct")
    @Story("Update Product")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify seller can update their product")
    public void testUpdateProduct() {
        TestModels.ProductRequest updateReq = TestModels.ProductRequest.builder()
                .name("Updated Product Name")
                .price(BigDecimal.valueOf(99.99))
                .build();

        TestModels.ProductResponse updated = productApiClient.updateProduct(sellerAuth, createdProduct.getId(), updateReq);

        assertThat(updated.getName()).isEqualTo("Updated Product Name");
        assertThat(updated.getPrice()).isEqualByComparingTo(BigDecimal.valueOf(99.99));
    }

    @Test(priority = 9, dependsOnMethods = "testCreateProduct")
    @Story("Stock Management")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify stock can be updated for a product")
    public void testUpdateProductStock() {
        productApiClient.updateStock(createdProduct.getId(), 50); // throws if not 204
        logStep("Stock updated successfully");
    }

    /**
     * ⭐ NEW — GET /api/v1/products/category/{categoryId} previously had
     * ZERO test coverage. Self-contained: creates its OWN product with an
     * explicit categoryId, rather than depending on the shared
     * class-level createdProduct fixture, whose categoryId is not
     * confirmed to be set by TestDataFactory.createRandomProduct().
     *
     * NOTE: TestModels.ProductRequest.builder().categoryId() takes a
     * String, not a UUID directly — confirmed via a compile error on the
     * first attempt (it took UUID). Fixed to pass categoryId.toString().
     */
    @Test(priority = 11)
    @Story("Get Products By Category")
    @Severity(SeverityLevel.CRITICAL)
    @Description("Verify products can be listed filtered by category")
    public void testGetProductsByCategory() {
        logStep("TEST: Creating a product with a known category, then verifying it's returned by category listing");

        UUID categoryId = UUID.randomUUID();
        TestModels.ProductRequest categorizedRequest = TestModels.ProductRequest.builder()
                .name("Category Test Product " + System.nanoTime())
                .price(BigDecimal.valueOf(29.99))
                .stockQuantity(50)
                .categoryId(categoryId.toString())
                .build();

        TestModels.ProductResponse categorizedProduct = productApiClient
                .createProductRaw(sellerAuth, categorizedRequest)
                .as(TestModels.ProductResponse.class);
        logStep("  ✓ Product created with categoryId=" + categoryId + ": " + categorizedProduct.getId());

        var response = productApiClient.getProductsByCategory(categoryId, 0, 10);
        var body = response.as(java.util.Map.class);

        assertThat(body.get("products")).as("Response should contain a products list").isNotNull();

        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> products =
                (java.util.List<java.util.Map<String, Object>>) body.get("products");

        boolean found = products.stream()
                .anyMatch(p -> categorizedProduct.getId().equals(p.get("id")));

        assertThat(found)
                .as("The product created with categoryId=" + categoryId + " should appear in that category's listing")
                .isTrue();

        logStep("✅ Category listing correctly returned the product created for that category");
    }

    @Test(priority = 10, dependsOnMethods = { "testUpdateProductStock"})
    @Story("Delete Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify seller can delete their product")
    public void testDeleteProduct() {
        logStep("Deleting product: " + createdProduct.getId());

        productApiClient.deleteProduct(sellerAuth, createdProduct.getId()); // throws if not 204

        var afterDelete = productApiClient.getProductRaw(createdProduct.getId());
        assertThat(afterDelete.getStatusCode()).isIn(200, 404);
    }
}
