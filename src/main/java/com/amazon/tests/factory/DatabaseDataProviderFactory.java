package com.amazon.tests.factory;

import com.amazon.tests.builder.DatabaseDataProviderBuilder;
import com.amazon.tests.config.DatabaseConfig;
import com.amazon.tests.provider.TestDataProvider;

public class DatabaseDataProviderFactory
        implements TestDataProviderFactory {

    @Override
    public TestDataProvider createProvider() {

        return DatabaseDataProviderBuilder.builder()
                .withDatabaseConfig(DatabaseConfig.fromEnvironment())
                .build();
    }
}
