package com.amazon.tests.provider;

public interface TestDataProvider {
    <T> T load(Class<T> clazz, String key);
}
