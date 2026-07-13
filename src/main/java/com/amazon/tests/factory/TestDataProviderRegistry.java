package com.amazon.tests.factory;

import com.amazon.tests.config.TestDataSourceType;
import com.amazon.tests.factory.excel.ExcelDataProviderFactory;

import java.util.EnumMap;
import java.util.Map;

public class TestDataProviderRegistry {

    private final Map<TestDataSourceType,
                TestDataProviderFactory> factories = new EnumMap<>(TestDataSourceType.class);

    public TestDataProviderRegistry() {

        factories.put(TestDataSourceType.JSON,
                new JsonDataProviderFactory());

        factories.put(TestDataSourceType.EXCEL,
                new ExcelDataProviderFactory());

        factories.put(TestDataSourceType.DATABASE,
                new DatabaseDataProviderFactory());

        factories.put(TestDataSourceType.SEEDER,
                new SeederDataProviderFactory());
    }

    public TestDataProviderFactory resolve(
            TestDataSourceType source) {

        TestDataProviderFactory factory = factories.get(source);

        if (factory == null) {
            throw new IllegalArgumentException(
                    "No factory registered for " + source);
        }

        return factory;
    }
}
