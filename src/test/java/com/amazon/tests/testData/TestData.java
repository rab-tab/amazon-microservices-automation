package com.amazon.tests.testData;

public final class TestData {
    private TestData() {
    }

    public static UserData user() {
        return new UserData();
    }

    public static ProductData product() {
        return new ProductData();
    }

    public static OrderData order() {
        return new OrderData();
    }
}
