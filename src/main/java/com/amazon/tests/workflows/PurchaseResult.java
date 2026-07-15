package com.amazon.tests.workflows;

import com.amazon.tests.models.TestModels;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PurchaseResult {
    private TestModels.RegisterRequest customer;

    private TestModels.AuthResponse customerAuth;

    private TestModels.AuthResponse sellerAuth;

    private TestModels.ProductResponse product;

    private TestModels.OrderResponse order;
}
