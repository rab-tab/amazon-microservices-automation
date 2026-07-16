package com.amazon.tests.validators;

import com.amazon.tests.workflows.PurchaseResult;

public class PurchaseValidator {
    private final ProductValidator productValidator = new ProductValidator();
    private final OrderValidator orderValidator = new OrderValidator();
    private final PaymentValidator paymentValidator = new PaymentValidator();

    public void verifyPurchaseCompleted(PurchaseResult purchase) {

        productValidator.verifyProductCreated(purchase);
        orderValidator.verifyOrderCreated(purchase);
        paymentValidator.verifySuccessfulPayment(purchase);
    }

    public void verifySingleItemPurchaseCompleted(PurchaseResult purchase) {

        verifyPurchaseCompleted(purchase);
        orderValidator.verifySingleItem(purchase);
    }
    public void verifyMultiItemPurchaseCompleted(PurchaseResult purchase,
                                                 int minItems,
                                                 int maxItems) {

        verifyPurchaseCompleted(purchase);

        orderValidator.verifyMultiItemOrder(
                purchase,
                minItems,
                maxItems);
    }

    public void verifyOrderCancelled(PurchaseResult purchase) {

        orderValidator.verifyCancelled(purchase);
        productValidator.verifyProductIsActive(purchase);
        productValidator.verifyProductBelongsToSeller(purchase);
    }
    public void verifyOrderRefunded(PurchaseResult purchase) {

    }

    public void verifyOrderReturned(PurchaseResult purchase) {

    }

    public void verifyOrderDelivered(PurchaseResult purchase) {

    }
}
