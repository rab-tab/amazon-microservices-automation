package com.amazon.tests.regression.productFlow;


import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.testData.TestDataFactory;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Epic("Amazon Microservices")
@Feature("Product Catalog - Negative")
public class ProductApiNegativeTest extends BaseTest {

    private TestModels.AuthResponse sellerAuth;
    private ProductApiClient productApiClient;

    @BeforeClass
    public void setup() {
        PurchaseResult result = PurchaseWorkflow.start(context.getExecutor(),authStrategy)
                .registerSeller()
                .execute();

        sellerAuth = result.getSellerAuth();
        productApiClient = new ProductApiClient(context.getExecutor());
    }

    @Test
    @Story("Create Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify product creation fails with negative price")
    public void testCreateProductWithNegativePrice() {
        TestModels.ProductRequest invalidProduct = TestDataFactory.createProductWithPrice(-10.0);

        ServiceResponse response = productApiClient.createProductRaw(sellerAuth, invalidProduct);

        assertThat(response.getStatusCode()).isEqualTo(400);
        var body = response.as(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) body.get("validationErrors")).get("price")).isNotNull();
    }

    @Test
    @Story("Create Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify product creation fails with missing required fields")
    public void testCreateProductWithMissingName() {
        TestModels.ProductRequest noName = TestDataFactory.createRandomProduct();
        noName.setName(null);

        ServiceResponse response = productApiClient.createProductRaw(sellerAuth, noName);

        assertThat(response.getStatusCode()).isEqualTo(400);
    }

    @Test
    @Story("Get Product")
    @Severity(SeverityLevel.NORMAL)
    @Description("Verify 404 is returned for non-existent product")
    public void testGetNonExistentProduct() {
        ServiceResponse response = productApiClient.getProductRaw("b10b2a0c-84ed-4511-9097-e17dfa74bb15");
        assertThat(response.getStatusCode()).isEqualTo(404);
    }
}
