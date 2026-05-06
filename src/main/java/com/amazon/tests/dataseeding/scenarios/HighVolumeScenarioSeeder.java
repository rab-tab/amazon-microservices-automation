// HighVolumeScenarioSeeder.java
package com.amazon.tests.dataseeding.scenarios;

import com.amazon.tests.dataseeding.core.*;
import com.amazon.tests.dataseeding.seeders.*;
import com.amazon.tests.models.TestModels;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Seeds high-volume scenario for load/performance testing
 * Default: 100 users, 200 products, 500+ orders
 */
@Slf4j
public class HighVolumeScenarioSeeder extends BaseSeedingManager<HighVolumeScenarioSeeder.HighVolumeScenarioData> {

    private final int userCount;
    private final int productCount;
    private final int ordersPerUser;

    private UserSeeder userSeeder;
    private ProductSeeder productSeeder;
    private final List<OrderSeeder> orderSeeders = new ArrayList<>();

    private HighVolumeScenarioSeeder(Builder builder) {
        super(builder.context);
        this.userCount = builder.userCount;
        this.productCount = builder.productCount;
        this.ordersPerUser = builder.ordersPerUser;
    }

    @Override
    protected HighVolumeScenarioData doSeed() throws Exception {
        long startTime = System.currentTimeMillis();
        log.info("Seeding high-volume scenario: {} users, {} products, ~{} orders",
                userCount, productCount, userCount * ordersPerUser);

        // Seed users in parallel
        userSeeder = UserSeeder.builder(context)
                .count(userCount)
                .parallel()
                .build();
        UserSeeder.UserSeedResult userResult = userSeeder.seed();

        // Seed products in parallel
        productSeeder = ProductSeeder.builder(context)
                .count(productCount)
                .parallel()
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        // Wait for data propagation
        waitForPropagation(2000);

        // Create orders for each user
        List<TestModels.OrderResponse> allOrders = new ArrayList<>();

        for (TestModels.UserResponse user : userResult.getUsers()) {
            OrderSeeder orderSeeder = OrderSeeder.builder(context)
                    .forUser(user)
                    .withProducts(productResult.getProducts())
                    .count(ordersPerUser)
                    .itemsPerOrder(1, 5)
                    .build();

            OrderSeeder.OrderSeedResult orderResult = orderSeeder.seed();
            allOrders.addAll(orderResult.getOrders());
            orderSeeders.add(orderSeeder);
        }

        long duration = System.currentTimeMillis() - startTime;

        log.info("High-volume seeding completed in {}ms", duration);

        return HighVolumeScenarioData.builder()
                .namespace(getNamespace())
                .users(userResult.getUsers())
                .products(productResult.getProducts())
                .orders(allOrders)
                .seedingDurationMs(duration)
                .build();
    }

    @Override
    protected void doCleanup() {
        orderSeeders.forEach(OrderSeeder::cleanup);
        if (productSeeder != null) productSeeder.cleanup();
        if (userSeeder != null) userSeeder.cleanup();
        orderSeeders.clear();
    }

    // Builder

    public static Builder builder(SeedingContext context) {
        return new Builder(context);
    }

    public static class Builder {
        private final SeedingContext context;
        private int userCount = 100;
        private int productCount = 200;
        private int ordersPerUser = 5;

        private Builder(SeedingContext context) {
            this.context = context;
        }

        public Builder users(int count) {
            this.userCount = count;
            return this;
        }

        public Builder products(int count) {
            this.productCount = count;
            return this;
        }

        public Builder ordersPerUser(int count) {
            this.ordersPerUser = count;
            return this;
        }

        public HighVolumeScenarioSeeder build() {
            return new HighVolumeScenarioSeeder(this);
        }
    }

    // Result

    @lombok.Data
    @lombok.Builder
    public static class HighVolumeScenarioData {
        private String namespace;
        private List<TestModels.UserResponse> users;
        private List<TestModels.ProductResponse> products;
        private List<TestModels.OrderResponse> orders;
        private long seedingDurationMs;
    }
}
