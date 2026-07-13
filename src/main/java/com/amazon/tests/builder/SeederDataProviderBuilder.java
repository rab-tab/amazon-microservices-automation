package com.amazon.tests.builder;


import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.dataseeding.seeders.OrderSeeder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.provider.SeederDataProvider;

public class SeederDataProviderBuilder {

    private SeedingContext context;

    private UserSeeder userSeeder;
    private OrderSeeder orderSeeder;
    private ProductSeeder productSeeder;

    private SeederDataProviderBuilder() {
    }

    public static SeederDataProviderBuilder builder() {
        return new SeederDataProviderBuilder();
    }

    /**
     * Mandatory configuration.
     */
    public SeederDataProviderBuilder withContext(SeedingContext context) {
        this.context = context;
        return this;
    }

    /**
     * Optional override.
     */
    public SeederDataProviderBuilder withUserSeeder(UserSeeder userSeeder) {
        this.userSeeder = userSeeder;
        return this;
    }

    /**
     * Optional override.
     */
    public SeederDataProviderBuilder withOrderSeeder(OrderSeeder orderSeeder) {
        this.orderSeeder = orderSeeder;
        return this;
    }

    /**
     * Optional override.
     */
    public SeederDataProviderBuilder withProductSeeder(ProductSeeder productSeeder) {
        this.productSeeder = productSeeder;
        return this;
    }

    public SeederDataProvider build() {

        validate();

        if (userSeeder == null) {
            userSeeder = UserSeeder.builder(context).build();
        }

        if (orderSeeder == null) {
            orderSeeder = OrderSeeder.builder(context).build();
        }

        if (productSeeder == null) {
            productSeeder = ProductSeeder.builder(context).build();
        }

        return new SeederDataProvider(
                userSeeder,
                orderSeeder,
                productSeeder);
    }

    private void validate() {

        if (context == null) {
            throw new IllegalStateException(
                    "SeedingContext is mandatory.");
        }
    }
}
