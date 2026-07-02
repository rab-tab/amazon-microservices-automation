package com.amazon.tests.utils.assertions;

import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
public final class OrderAssertions {

    private OrderAssertions() {
    }

    public static void assertPaymentFailure(
            Response orderResponse,String expectedFailureReason, boolean isExpectedRetryable,boolean isExpectFraudScore
            ) {

        String finalStatus =
                orderResponse.jsonPath().getString("status");

        String storedFailureReason =
                orderResponse.jsonPath().getString("paymentFailureReason");

        Boolean retryable =
                orderResponse.jsonPath().getBoolean("paymentRetryable");

        String paymentId =
                orderResponse.jsonPath().getString("paymentId");

        // Assert final state
        assertThat(finalStatus)
                .as("Order should be compensated to PAYMENT_FAILED")
                .isEqualTo("PAYMENT_FAILED");

        assertThat(storedFailureReason)
                .as("Failure reason should be stored in order")
                .contains(expectedFailureReason);

        assertThat(retryable)
                .as("Retryable flag should match scenario expectation")
                .isEqualTo(isExpectedRetryable);

        assertThat(paymentId)
                .as("No payment ID should be assigned on failure")
                .isNullOrEmpty();

        if (isExpectFraudScore) {

            Integer fraudScore =
                    orderResponse.jsonPath().getInt("paymentFraudScore");

            assertThat(fraudScore)
                    .as("Fraud score should be stored")
                    .isGreaterThan(90);
        }
        log.info("  ✓ Final order status: {}", finalStatus);
        log.info("  ✓ Failure reason: {}", storedFailureReason);
        log.info("  ✓ Retryable: {}", retryable);
    }
}
