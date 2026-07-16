package com.amazon.tests.workflows;

import com.amazon.tests.models.TestModels;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PurchaseResult {
    private TestModels.RegisterRequest customer;

    private TestModels.AuthResponse customerAuth;

    private TestModels.AuthResponse sellerAuth;

    private List<TestModels.ProductResponse> products = new ArrayList<>();

    private TestModels.OrderResponse order;

    public TestModels.ProductResponse getFirstProduct() {
        return products.get(0);
    }
}
