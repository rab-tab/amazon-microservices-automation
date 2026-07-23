package com.amazon.tests.utils.apiClients;

import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.*;
import com.amazon.tests.utils.testData.TestDataFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public class ProductApiClient {

    private final RequestExecutor executor;

    public ProductApiClient(RequestExecutor executor) {
        this.executor = executor;
    }

    // ============================================================
    // CREATE
    // ============================================================

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData) {
        return createProductInternal(sellerData, TestDataFactory.createProductWithPrice(49.99), 201)
                .as(TestModels.ProductResponse.class);
    }

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData, ProductType type) {
        double price = switch (type) {
            case CHEAP     -> ThreadLocalRandom.current().nextDouble(1.0, 20.0);
            case EXPENSIVE -> ThreadLocalRandom.current().nextDouble(100.0, 1000.0);
            default        -> ThreadLocalRandom.current().nextDouble(20.0, 100.0);
        };
        return createProductInternal(sellerData, TestDataFactory.createProductWithPrice(price), 201)
                .as(TestModels.ProductResponse.class);
    }
    public ServiceResponse browseProducts(int page, int size) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/products")
                .attribute(RequestAttributes.QUERY_PARAMS, Map.of("page", page, "size", size))
                .targetService(ServiceType.PRODUCT)
                .build();
        return requireSuccess(executor.execute(request), 200);
    }

    public TestModels.ProductResponse createProduct(TestModels.AuthResponse sellerData, double price, int stockQuantity) {
        return createProductInternal(sellerData, TestDataFactory.createProductWithPriceAndStock(price, stockQuantity), 201)
                .as(TestModels.ProductResponse.class);
    }

    /** Non-throwing variant for negative tests — caller inspects status/body directly. */
    public ServiceResponse createProductRaw(TestModels.AuthResponse sellerData, TestModels.ProductRequest payload) {
        return buildAndExecuteCreate(sellerData, payload);
    }

    private ServiceResponse createProductInternal(TestModels.AuthResponse sellerData,
                                                  TestModels.ProductRequest payload, int expectedStatus) {
        ServiceResponse response = buildAndExecuteCreate(sellerData, payload);
        TestModels.ProductResponse product = requireSuccess(response, expectedStatus).as(TestModels.ProductResponse.class);
        assertThat(product.getId()).isNotBlank();
        assertThat(product.getStatus()).isEqualTo("ACTIVE");
        log.info("Product created: {} - {} (${})", product.getId(), product.getName(), product.getPrice());
        return response;
    }

    private ServiceResponse buildAndExecuteCreate(TestModels.AuthResponse sellerData, TestModels.ProductRequest payload) {
        ServiceRequest.ServiceRequestBuilder builder = ServiceRequest.builder()
                .method(HttpMethod.POST)
                .endpoint("/api/v1/products")
                .payload(payload)
                .targetService(ServiceType.PRODUCT);

        if (sellerData != null) {
            builder.token(sellerData.getAccessToken())
                    .header("X-User-Id", sellerData.getUser().getId());
        }
        return executor.execute(builder.build());
    }

    // ============================================================
    // READ
    // ============================================================

    public TestModels.ProductResponse getProduct(String id) {
        return requireSuccess(getProductRaw(id), 200).as(TestModels.ProductResponse.class);
    }

    public ServiceResponse getProductRaw(String id) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/products/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", id))
                .targetService(ServiceType.PRODUCT)
                .build();
        return executor.execute(request);
    }

    public ServiceResponse getAllProducts(int page, int size) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/products")
                .attribute(RequestAttributes.QUERY_PARAMS, Map.of("page", page, "size", size))
                .targetService(ServiceType.PRODUCT)
                .build();
        return requireSuccess(executor.execute(request), 200);
    }

    public ServiceResponse searchProducts(String query) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.GET)
                .endpoint("/api/v1/products/search")
                .attribute(RequestAttributes.QUERY_PARAMS, Map.of("q", query))
                .targetService(ServiceType.PRODUCT)
                .build();
        return requireSuccess(executor.execute(request), 200);
    }

    // ============================================================
    // UPDATE
    // ============================================================

    public TestModels.ProductResponse updateProduct(TestModels.AuthResponse sellerData, String productId,
                                                    TestModels.ProductRequest payload) {
        return requireSuccess(updateProductRaw(sellerData, productId, payload), 200)
                .as(TestModels.ProductResponse.class);
    }

    public ServiceResponse updateProductRaw(TestModels.AuthResponse sellerData, String productId,
                                            TestModels.ProductRequest payload) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.PUT)
                .endpoint("/api/v1/products/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", productId))
                .payload(payload)
                .token(sellerData.getAccessToken())
                .header("X-User-Id", sellerData.getUser().getId())
                .targetService(ServiceType.PRODUCT)
                .build();
        return executor.execute(request);
    }

    public void updateStock(String productId, int quantity) {
        requireSuccess(updateStockRaw(productId, quantity), 204);
    }

    public ServiceResponse updateStockRaw(String productId, int quantity) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.PATCH)
                .endpoint("/api/v1/products/{id}/stock")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", productId))
                .attribute(RequestAttributes.QUERY_PARAMS, Map.of("quantity", quantity))
                .targetService(ServiceType.PRODUCT)
                .build();
        return executor.execute(request);
    }

    // ============================================================
    // DELETE
    // ============================================================

    public void deleteProduct(TestModels.AuthResponse sellerData, String productId) {
        requireSuccess(deleteProductRaw(sellerData, productId), 204);
    }

    public ServiceResponse deleteProductRaw(TestModels.AuthResponse sellerData, String productId) {
        ServiceRequest request = ServiceRequest.builder()
                .method(HttpMethod.DELETE)
                .endpoint("/api/v1/products/{id}")
                .attribute(RequestAttributes.PATH_PARAMS, Map.of("id", productId))
                .token(sellerData.getAccessToken())
                .header("X-User-Id", sellerData.getUser().getId())
                .targetService(ServiceType.PRODUCT)
                .build();
        return executor.execute(request);
    }

    // ============================================================
    // BULK CREATE
    // ============================================================

    public List<TestModels.ProductResponse> createProducts(TestModels.AuthResponse sellerData, int count, ProductType type) {
        List<TestModels.ProductResponse> products = new ArrayList<>();
        for (int i = 0; i < count; i++) products.add(createProduct(sellerData, type));
        return products;
    }

    public List<TestModels.ProductResponse> createProducts(TestModels.AuthResponse sellerData, int count) {
        List<TestModels.ProductResponse> products = new ArrayList<>();
        for (int i = 0; i < count; i++) products.add(createProduct(sellerData));
        return products;
    }

    // ============================================================
    // PRIVATE HELPERS
    // ============================================================

    private ServiceResponse requireSuccess(ServiceResponse response, int expectedStatus) {
        if (response.getStatusCode() != expectedStatus) {
            throw new IllegalStateException(String.format(
                    "Expected status %d but got %d. Body: %s",
                    expectedStatus, response.getStatusCode(), response.getBody()));
        }
        return response;
    }
}