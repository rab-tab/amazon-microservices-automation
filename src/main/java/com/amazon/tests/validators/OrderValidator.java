package com.amazon.tests.validators;


import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import org.awaitility.Awaitility;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.testng.Assert.*;

public class OrderValidator {

    private final OrderApiClient orderApiClient;

    public OrderValidator(OrderApiClient orderApiClient) {
        this.orderApiClient = orderApiClient;
    }

    private TestModels.OrderResponse getOrder(PurchaseResult purchase) {
        return orderApiClient.getOrder(
                purchase.getCustomerAuth().getAccessToken(),
                purchase.getCustomerAuth().getUser().getId(),
                purchase.getOrder().getId());
    }

    public void verifyMinimumItems(TestModels.OrderResponse order,
                                   int minimumItems) {

        assertNotNull(order.getItems(), "Order items should exist");

        assertTrue(order.getItems().size() >= minimumItems,
                String.format("Order should contain at least %d items", minimumItems));
    }

    public void verifyOrderCreated(PurchaseResult purchase) {

        TestModels.OrderResponse order = purchase.getOrder();

        verifyOrderIdGenerated(order);
        verifyConfirmed(order);
        verifyHasItems(order);
        verifyTotalAmount(order);
        verifyPositiveAmount(order);

        verifyOrderBelongsToUser(purchase);
        verifyProduct(purchase);
    }

    public void verifyOrderIdGenerated(TestModels.OrderResponse order) {

        assertNotNull(order.getId(),
                "Order ID should be generated");
    }

    public void verifyConfirmed(TestModels.OrderResponse order) {

        assertEquals(order.getStatus(),
                "PENDING",
                "Order should be in PENDING status");
    }

    public void verifyHasItems(TestModels.OrderResponse order) {

        assertNotNull(order.getItems(),
                "Order items should exist");

        assertFalse(order.getItems().isEmpty(),
                "Order should contain at least one item");
    }

    public void verifyTotalAmount(TestModels.OrderResponse order) {

        assertNotNull(order.getTotalAmount(),
                "Order should have total amount");
    }

    public void verifyPositiveAmount(TestModels.OrderResponse order) {

        assertTrue(order.getTotalAmount().compareTo(BigDecimal.ZERO) > 0,
                "Total amount should be greater than zero");
    }

    public void verifySingleItemOrder(PurchaseResult purchase) {

        verifyOrderCreated(purchase);

        assertEquals(
                purchase.getOrder().getItems().size(),
                1,
                "Order should contain exactly one item");
    }
    public void verifyMultiItemOrder(PurchaseResult purchase,
                                     int minItems,
                                     int maxItems) {

        verifyOrderCreated(purchase);

        List<TestModels.OrderItemResponse> items =
                purchase.getOrder().getItems();

        assertTrue(
                items.size() >= minItems && items.size() <= maxItems,
                String.format("Order should contain between %d and %d items",
                        minItems, maxItems));

        items.forEach(this::verifyOrderItem);
    }
    private void verifyOrderItem(TestModels.OrderItemResponse item) {

        assertNotNull(item.getProductId(),
                "Each item should have product ID");

        assertNotNull(item.getProductName(),
                "Each item should have product name");

        assertTrue(item.getQuantity() > 0,
                "Each item should have quantity > 0");

        assertTrue(item.getUnitPrice().compareTo(BigDecimal.ZERO) > 0,
                "Each item should have unit price > 0");
    }


    public void verifyConfirmed(PurchaseResult purchase) {
        Awaitility.await()
                .alias("Waiting for order confirmation")
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {

                    TestModels.OrderResponse order =
                            getOrder(purchase);

                    assertThat(order.getStatus())
                            .isEqualTo("CONFIRMED");

                });
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

        Set<String> productIds = purchase.getProducts().stream()
                .map(TestModels.ProductResponse::getId)
                .collect(Collectors.toSet());

        getOrder(purchase).getItems().forEach(item ->
                assertThat(productIds)
                        .contains(item.getProductId()));
    }
    public void verifySingleItem(PurchaseResult purchase) {
        assertEquals(
                purchase.getOrder().getItems().size(),
                1,
                "Order should contain exactly one item");
    }
}