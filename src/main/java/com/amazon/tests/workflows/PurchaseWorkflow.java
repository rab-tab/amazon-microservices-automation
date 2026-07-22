package com.amazon.tests.workflows;

import com.amazon.tests.auth.AuthStrategy;
import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.testData.TestDataFactory;

import java.util.ArrayList;
import java.util.List;

public class PurchaseWorkflow {
    private List<TestModels.ProductResponse> products = new ArrayList<>();

    private final AuthApiClient authApiClient;
    private final ProductApiClient productApiClient;
    private final OrderApiClient orderApiClient;
    private final PaymentApiClient paymentApiClient;
    private static KafkaTestConsumer consumer = null;
    private final PurchaseResult result = new PurchaseResult();

    private PurchaseWorkflow(RequestExecutor executor, AuthStrategy authStrategy, KafkaTestConsumer consumer) {
        this.authApiClient = new AuthApiClient(executor);
        this.productApiClient = new ProductApiClient(executor);
        this.consumer = consumer;
        this.orderApiClient = new OrderApiClient(authStrategy, executor);
        this.paymentApiClient = new PaymentApiClient(this.consumer, executor);
    }

    public static PurchaseWorkflow start(RequestExecutor executor, AuthStrategy authStrategy) {
        return new PurchaseWorkflow(executor, authStrategy, consumer);
    }



    public PurchaseWorkflow registerCustomer() {

        result.setCustomer(authApiClient.registerCustomer());

        return this;
    }

    public PurchaseWorkflow loginCustomer() {

        TestModels.AuthResponse auth =
                authApiClient.login(
                        result.getCustomer().getEmail(),
                        result.getCustomer().getPassword());

        result.setCustomerAuth(auth);

        return this;
    }

    public PurchaseWorkflow registerSeller() {

        result.setSellerAuth(authApiClient.registerSeller());

        return this;
    }


    public PurchaseWorkflow createProduct(int count) {

        result.getProducts().addAll(
                productApiClient.createProducts(result.getSellerAuth(), count));

        return this;
    }
    public PurchaseWorkflow createProductWithStock(double price, int stockQuantity) {
        result.getProducts().add(productApiClient.createProduct(result.getSellerAuth(), price, stockQuantity));
        return this;
    }

    public PurchaseWorkflow createProducts(int count, ProductType type) {

        List<TestModels.ProductResponse> products =
                productApiClient.createProducts(
                        result.getSellerAuth(),
                        count,
                        type);

        result.getProducts().addAll(products);

        return this;
    }

    public PurchaseWorkflow createOrderWithScenario(String testScenario) {
        TestModels.OrderResponse order = orderApiClient.createOrderWithTestScenario(
                result.getCustomerAuth().getUser().getId(),
                TestDataFactory.newIdempotencyKey(),
                result.getProducts(),
                testScenario);
        result.setOrder(order);
        return this;
    }
    public PurchaseWorkflow browseProducts() {

        productApiClient.browseProducts();

        return this;
    }


    public PurchaseWorkflow viewProduct() {
        productApiClient.getProduct(result.getFirstProduct().getId());
        return this;
    }
    public PurchaseWorkflow createOrder() {

        String userId = result.getCustomerAuth().getUser().getId();
        String idempotencyKey = java.util.UUID.randomUUID().toString();   // or however you generate these

        TestModels.OrderResponse order = orderApiClient.createOrder(
                userId, idempotencyKey, result.getProducts());
        result.setOrder(order);
        return this;
    }

    public PurchaseWorkflow cancelOrder() {

        orderApiClient.cancelOrder(
                result.getOrder().getId(),
                result.getCustomerAuth().getAccessToken(),
                result.getCustomerAuth().getUser().getId());

        return this;
    }

    public PurchaseResult execute() {

        return result;
    }
    /*public PurchaseWorkflow createMultiItemOrder(int minItems, int maxItems) {

        TestModels.OrderResponse order =
                orderFacade.createMultiItemOrder(
                        result.getProduct().getId(),
                        result.getCustomerAuth().getUser().getId(),
                        result.getCustomerAuth().getAccessToken(),
                        minItems,
                        maxItems);

        result.setOrder(order);

        return this;
    }*/

}
