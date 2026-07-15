package com.amazon.tests.validators;


import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.OrderFacade;
import com.amazon.tests.workflows.PurchaseResult;

import static org.assertj.core.api.Assertions.assertThat;

public class OrderValidator {

    private final OrderFacade orderFacade = new OrderFacade();

    private TestModels.OrderResponse getOrder(PurchaseResult purchase) {
        return orderFacade.getOrder(
                purchase.getCustomerAuth().getAccessToken(),
                purchase.getCustomerAuth().getUser().getId(),
                purchase.getOrder().getId());
    }

    public void verifyConfirmed(PurchaseResult purchase) {
        assertThat(getOrder(purchase).getStatus())
                .isEqualTo("CONFIRMED");
    }

    public void verifyCancelled(PurchaseResult purchase) {
        assertThat(getOrder(purchase).getStatus())
                .isEqualTo("CANCELLED");
    }

    public void verifyOrderBelongsToUser(PurchaseResult purchase) {

        assertThat(getOrder(purchase).getUserId())
                .isEqualTo(purchase.getCustomerAuth().getUser().getId());
    }

    public void verifyProduct(PurchaseResult purchase) {
        assertThat(getOrder(purchase).getItems().get(0).getProductId())
                .isEqualTo(purchase.getProduct().getId());
    }
}