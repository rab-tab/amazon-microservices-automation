// OrderSeeder.java
package com.amazon.tests.dataseeding.seeders;

import com.amazon.tests.dataseeding.builders.OrderBuilder;
import com.amazon.tests.dataseeding.core.BaseSeedingManager;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Seeder for Order entities
 */
@Slf4j
public class OrderSeeder extends BaseSeedingManager<OrderSeeder.OrderSeedResult> {

    private final TestModels.UserResponse user;
    private final List<TestModels.ProductResponse> products;
    private final int orderCount;
    private final int minItems;
    private final int maxItems;
    private final String userToken;

    private final List<TestModels.OrderResponse> createdOrders = new ArrayList<>();

    private OrderSeeder(Builder builder) {
        super(builder.context);
        this.user = builder.user;
        this.products = builder.products;
        this.orderCount = builder.orderCount;
        this.minItems = builder.minItems;
        this.maxItems = builder.maxItems;
        this.userToken = builder.userToken;
    }

    @Override
    protected OrderSeedResult doSeed() throws Exception {
        if (user == null) {
            throw new IllegalStateException("User is required for order seeding");
        }
        if (products == null || products.isEmpty()) {
            throw new IllegalStateException("Products are required for order seeding");
        }

        for (int i = 0; i < orderCount; i++) {
            TestModels.OrderResponse order = createOrder();
            createdOrders.add(order);
        }

        context.incrementStat("orders_created");

        return OrderSeedResult.builder()
                .namespace(getNamespace())
                .user(user)
                .orders(createdOrders)
                .count(createdOrders.size())
                .build();
    }

    private TestModels.OrderResponse    createOrder() {
        log.debug("Creating order for user: {}", user.getEmail());

        // Select random products
        int itemCount = new Random().nextInt(maxItems - minItems + 1) + minItems;
        List<TestModels.ProductResponse> selectedProducts = selectRandomProducts(itemCount);

        // Build order
        OrderBuilder builder = OrderBuilder.anOrder()
                .withNamespace(getNamespace());

        selectedProducts.forEach(builder::addRandomItem);

        TestModels.CreateOrderRequest request = builder.build();

        // ✅ Get user token from cache (cached by UserSeeder)
        String userToken = context.getCached("user_token_" + user.getId(), String.class);

        if (userToken == null) {
            throw new IllegalStateException(
                    "User token not found in cache for user: " + user.getId() +
                            ". Make sure the user was created via UserSeeder which caches the token."
            );
        }

        log.debug("Retrieved token for user {} from cache", user.getId());

        // ✅ Get authenticated spec (includes base URL and auth header)
        RequestSpecification spec = context.getRestAssuredConfig().getOrderServiceSpec(userToken);

        // ✅ Make API call with proper spec
        TestModels.OrderResponse order = context.getRestClient().post(context.getConfig().baseUrl()+
                "/api/orders",  // Gateway path
                spec,
                request,
                TestModels.OrderResponse.class
        );

        // Register cleanup
        context.registerCleanup(
                "Order: " + order.getId(),
                () -> deleteOrder(order.getId())
        );

        log.info("Created order: {} for user: {}", order.getId(), user.getEmail());
        return order;
    }



    private List<TestModels.ProductResponse> selectRandomProducts(int count) {
        List<TestModels.ProductResponse> shuffled = new ArrayList<>(products);
        Collections.shuffle(shuffled);
        return shuffled.subList(0, Math.min(count, shuffled.size()));
    }

    @Override
    protected void doCleanup() {
        createdOrders.clear();
    }

    private void deleteOrder(String orderId) {
        try {
            Map<String, String> headers = new HashMap<>();
            if (userToken != null) {
                headers.put("Authorization", "Bearer " + userToken);
            }

            context.getRestClient().delete(
                    context.getConfig().baseUrl() + "/api/orders/" + orderId,
                    headers
            );
            log.debug("Deleted order: {}", orderId);
        } catch (Exception e) {
            log.warn("Failed to delete order: {}", orderId, e);
        }
    }

    // Builder

    public static Builder builder(SeedingContext context) {
        return new Builder(context);
    }

    public static class Builder {
        private final SeedingContext context;
        private TestModels.UserResponse user;
        private List<TestModels.ProductResponse> products;
        private int orderCount = 1;
        private int minItems = 1;
        private int maxItems = 3;
        private String userToken;

        private Builder(SeedingContext context) {
            this.context = context;
        }

        public Builder forUser(TestModels.UserResponse user) {
            this.user = user;
            return this;
        }

        public Builder withProducts(List<TestModels.ProductResponse> products) {
            this.products = products;
            return this;
        }

        public Builder count(int count) {
            this.orderCount = count;
            return this;
        }

        public Builder itemsPerOrder(int min, int max) {
            this.minItems = min;
            this.maxItems = max;
            return this;
        }

        public Builder withUserToken(String token) {
            this.userToken = token;
            return this;
        }

        public OrderSeeder build() {
            return new OrderSeeder(this);
        }
    }

    // Result class

    @lombok.Data
    @lombok.Builder
    public static class OrderSeedResult {
        private String namespace;
        private TestModels.UserResponse user;
        private List<TestModels.OrderResponse> orders;
        private int count;

        public TestModels.OrderResponse getFirst() {
            return orders.isEmpty() ? null : orders.get(0);
        }
    }
}