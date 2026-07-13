package com.amazon.tests.factory;

import com.amazon.tests.config.TestDataConfig;
import com.amazon.tests.provider.TestDataProvider;

public class TestDataManager {


    private static final TestDataProviderRegistry registry =
            new TestDataProviderRegistry();

    public static <T> T load(Class<T> clazz,
                             String key) {

        TestDataProvider provider =
                registry
                        .resolve(TestDataConfig.getSource())
                        .createProvider();

        return provider.load(clazz, key);
    }
}
