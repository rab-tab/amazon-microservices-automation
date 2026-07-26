package com.amazon.tests.validators;

import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.PaymentApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.workflows.PurchaseResult;

public class PurchaseValidator {
    private final ProductValidator productValidator;
    private final OrderValidator orderValidator;
    private final PaymentValidator paymentValidator;
    public PurchaseValidator(ProductApiClient productApiClient,
                             OrderApiClient orderApiClient,
                             PaymentApiClient paymentApiClient) {
        this.productValidator = new ProductValidator(productApiClient);
        this.orderValidator = new OrderValidator(orderApiClient);
        this.paymentValidator = new PaymentValidator(paymentApiClient);
    }

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
        productValidator.verifyProductsRemainActive(purchase);
    }
    public void verifyOrderRefunded(PurchaseResult purchase) {

    }

    public void verifyOrderReturned(PurchaseResult purchase) {

    }

    public void verifyOrderDelivered(PurchaseResult purchase) {

    }
}
