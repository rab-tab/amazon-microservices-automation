package com.amazon.tests.dataseeding.builders;

import com.amazon.tests.models.TestModels;

import java.math.BigDecimal;
import java.util.List;

public class CreateOrderRequestPrototype implements Cloneable{
    private String productId;
    private String productName;
    private BigDecimal unitPrice;
    private Integer quantity;
    private String shippingAddress;

    private static final String EMPTY_ITEMS_MARKER = "__EMPTY_ITEMS__";

    private CreateOrderRequestPrototype() {
    }

    public static CreateOrderRequestPrototype validBaseline(TestModels.ProductResponse product){
        CreateOrderRequestPrototype p=new CreateOrderRequestPrototype();
        p.productId=product.getId();
        p.productName = product.getName();
        p.unitPrice = product.getPrice();
        p.quantity = 1;
        p.shippingAddress = "123 Test St";
        return p;
    }

    @Override
    public CreateOrderRequestPrototype clone() {
        try {
            return (CreateOrderRequestPrototype) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Cloneable class failed to clone — should be unreachable", e);
        }
    }
    public CreateOrderRequestPrototype withProductId(String productId) {
        this.productId = productId;
        return this;
    }

    public CreateOrderRequestPrototype withQuantity(Integer quantity) {
        this.quantity = quantity;
        return this;
    }

    public CreateOrderRequestPrototype withShippingAddress(String shippingAddress) {
        this.shippingAddress = shippingAddress;
        return this;
    }

    public CreateOrderRequestPrototype withEmptyItems() {
        this.productId = EMPTY_ITEMS_MARKER;
        return this;
    }

    /**
     * Materializes the current (possibly mutated) state into a real
     * CreateOrderRequest. Uses the same TestModels builder as OrderBuilder,
     * but WITHOUT OrderBuilder's fallback-generation behavior — negative
     * tests need to send exactly what's specified, including null/missing
     * values, not have them silently backfilled.
     */
    public TestModels.CreateOrderRequest build() {
        List<TestModels.OrderItemRequest> items;

        if (EMPTY_ITEMS_MARKER.equals(productId)) {
            items = List.of();
        } else {
            items = List.of(TestModels.OrderItemRequest.builder()
                    .productId(productId)
                    .productName(productName)
                    .unitPrice(unitPrice)
                    .quantity(quantity)
                    .build());
        }
        TestModels.CreateOrderRequest.CreateOrderRequestBuilder builder =
                TestModels.CreateOrderRequest.builder().items(items);

        // Deliberately NOT falling back to a generated address when null —
        // unlike OrderBuilder, this class exists to send exactly what each
        // negative scenario specifies, including a genuinely missing field.
        if (shippingAddress != null) {
            builder.shippingAddress(shippingAddress);
        }

        return builder.build();
    }

}
