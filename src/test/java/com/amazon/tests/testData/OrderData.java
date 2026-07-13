package com.amazon.tests.testData;

import com.amazon.tests.models.TestModels;

import java.util.List;


public class OrderData {

    private TestModels.UserResponse user;

    private List<TestModels.ProductResponse> products;

    private int minItems = 1;

    private int maxItems = 1;

    public OrderData forUser(TestModels.UserResponse user) {

        this.user = user;
        return this;
    }

    public OrderData withProducts(List<TestModels.ProductResponse> products) {

        this.products = products;
        return this;
    }

    public OrderData itemsPerOrder(int min,
                                   int max) {

        this.minItems = min;
        this.maxItems = max;

        return this;
    }

    public TestModels.OrderResponse create() {

        return null;
    }

    public TestModels.UserResponse getUser() {
        return user;
    }

    public List<TestModels.ProductResponse> getProducts() {
        return products;
    }

    public int getMinItems() {
        return minItems;
    }

    public int getMaxItems() {
        return maxItems;
    }
}