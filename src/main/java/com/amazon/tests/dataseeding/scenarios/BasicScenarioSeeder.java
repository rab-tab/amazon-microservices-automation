// BasicScenarioSeeder.java
package com.amazon.tests.dataseeding.scenarios;

import com.amazon.tests.dataseeding.core.*;
import com.amazon.tests.dataseeding.seeders.*;
import com.amazon.tests.models.TestModels;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Seeds basic test scenario: 1 user + 3 products
 */
@Slf4j
public class BasicScenarioSeeder extends BaseSeedingManager<BasicScenarioSeeder.BasicScenarioData> {

    private UserSeeder userSeeder;
    private ProductSeeder productSeeder;

    public BasicScenarioSeeder(SeedingContext context) {
        super(context);
    }

    @Override
    protected BasicScenarioData doSeed() throws Exception {
        log.info("Seeding basic scenario");

        // Seed 1 user
        userSeeder = UserSeeder.builder(context)
                .count(1)
                .build();
        UserSeeder.UserSeedResult userResult = userSeeder.seed();

        // Seed 3 products
        productSeeder = ProductSeeder.builder(context)
                .count(3)
                .parallel()
                .build();
        ProductSeeder.ProductSeedResult productResult = productSeeder.seed();

        return BasicScenarioData.builder()
                .namespace(getNamespace())
                .user(userResult.getFirst())
                .products(productResult.getProducts())
                .build();
    }

    @Override
    protected void doCleanup() {
        if (productSeeder != null) productSeeder.cleanup();
        if (userSeeder != null) userSeeder.cleanup();
    }

    @lombok.Data
    @lombok.Builder
    public static class BasicScenarioData {
        private String namespace;
        private TestModels.UserResponse user;
        private List<TestModels.ProductResponse> products;
    }
}
