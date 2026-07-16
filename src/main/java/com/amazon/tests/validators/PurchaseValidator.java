package com.amazon.tests.validators;

import com.amazon.tests.workflows.PurchaseResult;

public class PurchaseValidator {
    private final ProductValidator productValidator = new ProductValidator();
    private final OrderValidator orderValidator = new OrderValidator();
    private final PaymentValidator paymentValidator = new PaymentValidator();

    public void verifyPurchaseCompleted(PurchaseResult purchase) {

        productValidator.verifyProductIsActive(purchase);
        productValidator.verifyProductPrice(purchase);
        productValidator.verifyProductBelongsToSeller(purchase);

        orderValidator.verifyConfirmed(purchase);
        orderValidator.verifyOrderBelongsToUser(purchase);
        orderValidator.verifyProduct(purchase);

        paymentValidator.verifyPaymentSuccessful(purchase);
        paymentValidator.verifyOrder(purchase);
        paymentValidator.verifyUser(purchase);
        paymentValidator.verifyAmount(purchase);
        paymentValidator.verifyTransactionGenerated(purchase);
        paymentValidator.verifyNoFailureReason(purchase);
    }
}
