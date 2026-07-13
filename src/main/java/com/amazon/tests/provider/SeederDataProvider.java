package com.amazon.tests.provider;


import com.amazon.tests.dataseeding.seeders.OrderSeeder;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;

public class SeederDataProvider implements TestDataProvider {

    private final UserSeeder userSeeder;
    private final OrderSeeder orderSeeder;
    private final ProductSeeder productSeeder;

    public SeederDataProvider(UserSeeder userSeeder,
                              OrderSeeder orderSeeder,
                              ProductSeeder productSeeder) {

        this.userSeeder = userSeeder;
        this.orderSeeder = orderSeeder;
        this.productSeeder = productSeeder;
    }

    @Override
    public <T> T load(Class<T> clazz, String key) {

        System.out.println("Loading " + key + " using Seeder framework.");

        // TODO:
        // Delegate to appropriate seeder based on clazz

        return null;
    }
}
