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

        PurchaseResult result = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerSeller()
                .execute();

        sellerAuth = result.getSellerAuth();
        productApiClient = new ProductApiClient(context.getExecutor());
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

    @Test(priority = 10, dependsOnMethods = {"testUpdateProduct", "testUpdateProductStock"})
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