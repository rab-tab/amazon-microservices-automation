package com.amazon.tests.utils.concurrency;


import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;

public final class OrderStatusPoller {

    private OrderStatusPoller() {}

    public static TestModels.OrderResponse waitForStatus(OrderApiClient orderApiClient, String token,
                                                         String userId, String orderId,
                                                         String expectedStatus, Duration timeout) {
        try {
            return Awaitility.await()
                    .atMost(timeout)
                    .pollInterval(Duration.ofMillis(200))
                    .ignoreExceptions()
                    .until(() -> orderApiClient.getOrder(token, userId, orderId),
                            order -> expectedStatus.equals(order.getStatus()));
        } catch (ConditionTimeoutException e) {
            TestModels.OrderResponse last = orderApiClient.getOrder(token, userId, orderId);
            throw new AssertionError(String.format(
                    "Order %s did not reach status '%s' within %s. Current status: %s",
                    orderId, expectedStatus, timeout, last.getStatus()), e);
        }
    }
}