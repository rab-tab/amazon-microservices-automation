package com.amazon.tests.regression.orderCreationFlow;

import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.validators.OrderValidator;
import com.amazon.tests.validators.ProductValidator;
import com.amazon.tests.validators.PurchaseValidator;
import com.amazon.tests.workflows.PurchaseResult;
import lombok.Builder;
import lombok.Getter;

import java.util.function.BiConsumer;

@Getter
@Builder
public class OrderFlowScenario {
    private final String description;
    private final int productCount;
    private final ProductType productType;        // nullable — null means "use plain createProduct(count)"
    private final int additionalProductCount;      // for the "2 categories" case; 0 if unused
    private final ProductType additionalProductType;
    private final BiConsumer<PurchaseResult, OrderFlowValidators> validation;

    @Override
    public String toString() {
        return description;   // shown in TestNG reports instead of the object hash
    }
    public record OrderFlowValidators(
            PurchaseValidator purchaseValidator,
            OrderValidator orderValidator,
            ProductValidator productValidator) {}
}