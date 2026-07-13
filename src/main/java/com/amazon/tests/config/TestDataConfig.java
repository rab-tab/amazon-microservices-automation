package com.amazon.tests.config;

public class TestDataConfig {
    private static TestDataSourceType source =
            TestDataSourceType.JSON;
    private TestDataConfig() {
    }

    public static TestDataSourceType getSource() {
        return source;
    }

    public static void setSource(TestDataSourceType sourceType) {
        source = sourceType;
    }
}
