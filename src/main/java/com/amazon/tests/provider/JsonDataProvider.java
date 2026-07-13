package com.amazon.tests.provider;

public class JsonDataProvider implements TestDataProvider{
    @Override
    public <T> T load(Class<T> clazz, String key) {
        System.out.println("Loading " + key + " from JSON");
        return null;
    }
}
