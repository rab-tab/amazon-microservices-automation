package com.amazon.tests.utils;

/**
 * Producer-Consumer Pattern for Testing Order Creation at Scale
 *
 * Flow:
 * 1. Producers generate test data (create users, products)
 * 2. Queue stores ready-to-test order data
 * 3. Consumers execute order creation tests
 *
 * Benefits:
 * - 10-15x faster execution
 * - Parallel data generation and test execution
 * - Automatic load balancing
 */
public class OrderCreationTestData {
    private String userId;
    private String userName;
    private String email;
    private String productSku;
    private String productName;
    private double productPrice;
    private int quantity;
    private String shippingAddress;
    private boolean isPoison;

    // Regular constructor
    public OrderCreationTestData(String userId, String userName, String email,
                                 String productSku, String productName, double productPrice,
                                 int quantity, String shippingAddress) {
        this.userId = userId;
        this.userName = userName;
        this.email = email;
        this.productSku = productSku;
        this.productName = productName;
        this.productPrice = productPrice;
        this.quantity = quantity;
        this.shippingAddress = shippingAddress;
        this.isPoison = false;
    }

    // Poison pill constructor
    private OrderCreationTestData(boolean isPoison) {
        this.isPoison = isPoison;
    }

    // Poison pill to signal end of processing
    public static final OrderCreationTestData POISON_PILL = new OrderCreationTestData(true);

    // Getters
    public String getUserId() { return userId; }
    public String getUserName() { return userName; }
    public String getEmail() { return email; }
    public String getProductSku() { return productSku; }
    public String getProductName() { return productName; }
    public double getProductPrice() { return productPrice; }
    public int getQuantity() { return quantity; }
    public String getShippingAddress() { return shippingAddress; }
    public boolean isPoison() { return isPoison; }

    @Override
    public String toString() {
        return String.format("OrderData[user=%s, product=%s, qty=%d]",
                userId, productSku, quantity);
    }
}