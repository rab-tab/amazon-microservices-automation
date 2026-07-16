package com.amazon.tests.validators;


import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.PaymentFacade;
import com.amazon.tests.workflows.PurchaseResult;
import org.awaitility.Awaitility;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

public class PaymentValidator {

    private final PaymentFacade paymentFacade = new PaymentFacade();

    /**
     * Fetch latest payment details
     */
    private TestModels.PaymentResponse getPayment(PurchaseResult purchase) {

        return paymentFacade.getPayment(
                purchase.getOrder().getId());
    }

    public void verifySuccessfulPayment(PurchaseResult purchase) {

        verifyPaymentSuccessful(purchase);
        verifyOrder(purchase);
        verifyUser(purchase);
        verifyAmount(purchase);
        verifyTransactionGenerated(purchase);
        verifyNoFailureReason(purchase);
    }

    /**
     * Verify payment completed successfully
     */
    public void verifyPaymentSuccessful(PurchaseResult purchase) {

        Awaitility.await()
                .alias("Waiting for payment to complete")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1))
                .ignoreExceptions()
                .untilAsserted(() -> {

                    TestModels.PaymentResponse payment =
                            getPayment(purchase);

                    assertThat(payment).isNotNull();

                    assertThat(payment.getStatus())
                            .isEqualTo(TestModels.PaymentStatus.SUCCESS);

                });

    }

    /**
     * Verify payment belongs to the order
     */
    public void verifyOrder(PurchaseResult purchase) {

        assertThat(getPayment(purchase).getOrderId())
                .isEqualTo(purchase.getOrder().getId());
    }

    /**
     * Verify payment belongs to the customer
     */
    public void verifyUser(PurchaseResult purchase) {

        assertThat(getPayment(purchase).getUserId())
                .isEqualTo(purchase.getCustomerAuth().getUser().getId());
    }

    /**
     * Verify payment amount
     */
    public void verifyAmount(PurchaseResult purchase) {

        assertThat(getPayment(purchase).getAmount())
                .isEqualByComparingTo(
                        purchase.getOrder().getTotalAmount());
    }

    /**
     * Verify payment currency
     */
    public void verifyCurrency(PurchaseResult purchase, String expectedCurrency) {

        assertThat(getPayment(purchase).getCurrency())
                .isEqualTo(expectedCurrency);
    }

    /**
     * Verify transaction id generated
     */
    public void verifyTransactionGenerated(PurchaseResult purchase) {

        assertThat(getPayment(purchase).getTransactionId())
                .isNotBlank();
    }

    /**
     * Verify payment method
     */
    public void verifyPaymentMethod(PurchaseResult purchase,
                                    TestModels.PaymentMethod expectedMethod) {

        assertThat(getPayment(purchase).getPaymentMethod())
                .isEqualTo(expectedMethod);
    }

    /**
     * Verify no failure reason exists
     */
    public void verifyNoFailureReason(PurchaseResult purchase) {

        assertThat(getPayment(purchase).getFailureReason())
                .isNull();
    }
}