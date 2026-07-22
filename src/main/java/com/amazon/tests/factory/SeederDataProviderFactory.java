package com.amazon.tests.factory;

import com.amazon.tests.builder.SeederDataProviderBuilder;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.provider.TestDataProvider;
import org.aeonbits.owner.ConfigFactory;

public class SeederDataProviderFactory
        implements TestDataProviderFactory {
    TestConfig testConfig = ConfigFactory.create(TestConfig.class);

    @Override
    public TestDataProvider createProvider() {

        return SeederDataProviderBuilder.builder()
                .withContext(new SeedingContext("QA",
                        testConfig, executor))
                .build();
    }
}