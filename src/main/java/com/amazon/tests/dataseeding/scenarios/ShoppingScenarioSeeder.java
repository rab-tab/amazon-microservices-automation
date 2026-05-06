// ShoppingScenarioSeeder.java
package com.amazon.tests.dataseeding.scenarios;

import com.amazon.tests.dataseeding.core.*;
import com.amazon.tests.dataseeding.seeders.*;
import com.amazon.tests.models.TestModels;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Seeds shopping scenario: User + Products + Order
 */
@Slf4j
public class ShoppingScenarioSeeder extends BaseSeedingManager<ShoppingScenarioSeeder.ShoppingScenarioData> {

    private UserSeeder userSeeder;
    private ProductSeeder productSeeder;
    private OrderSeeder orderSeeder;

    public ShoppingScenarioSeeder(SeedingContext context) {
        super(context);
    }

    @Override
    protected ShoppingScenarioData doSeed() throws Exception {
        log.info("Seeding shopping scenario");

        // Seed user
        userSeeder = UserSeeder.builder(context).count(1).build();
        UserSeeder.UserSeedResult userResult = userSeeder.seed();
        TestModels.UserResponse user = userResult.getFirst();

        // Seed products
        productSeeder = ProductSeeder.builder(context)
                .count(5)
                .parallel()
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();
        List<TestModels.ProductResponse> allProducts = productResult.getProducts();

        // Wait for data propagation
        waitForPropagation(1000);

        // Create order with 3 products
        List<TestModels.ProductResponse> orderProducts = allProducts.subList(0, 3);

        orderSeeder = OrderSeeder.builder(context)
                .forUser(user)
                .withProducts(orderProducts)
                .count(1)
                .build();
        OrderSeeder.OrderSeedResult orderResult = orderSeeder.seed();

        return ShoppingScenarioData.builder()
                .namespace(getNamespace())
                .user(user)
                .allProducts(allProducts)
                .orderedProducts(orderProducts)
                .order(orderResult.getFirst())
                .build();
    }

    @Override
    protected void doCleanup() {
        if (orderSeeder != null) orderSeeder.cleanup();
        if (productSeeder != null) productSeeder.cleanup();
        if (userSeeder != null) userSeeder.cleanup();
    }

    @lombok.Data
    @lombok.Builder
    public static class ShoppingScenarioData {
        private String namespace;
        private TestModels.UserResponse user;
        private List<TestModels.ProductResponse> allProducts;
        private List<TestModels.ProductResponse> orderedProducts;
        private TestModels.OrderResponse order;
    }
}