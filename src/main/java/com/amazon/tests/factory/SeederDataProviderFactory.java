package com.amazon.tests.factory;

import com.amazon.tests.builder.SeederDataProviderBuilder;
import com.amazon.tests.config.TestConfig;
import com.amazon.tests.config.restAsssured.RestAssuredConfig;
import com.amazon.tests.config.restAsssured.RestClient;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.provider.TestDataProvider;
import com.amazon.tests.transport.RequestExecutor;
import com.amazon.tests.transport.RestHttpClient;
import org.aeonbits.owner.ConfigFactory;

public class SeederDataProviderFactory
        implements TestDataProviderFactory {

    TestConfig testConfig = ConfigFactory.create(TestConfig.class);

    @Override
    public TestDataProvider createProvider() {
        RestClient restClient = new RestClient();
        RestAssuredConfig restAssuredConfig = new RestAssuredConfig(testConfig);
        RequestExecutor executor = new RestHttpClient(restClient, restAssuredConfig);

        return SeederDataProviderBuilder.builder()
                .withContext(new SeedingContext("QA", testConfig, executor))
                .build();
    }
}