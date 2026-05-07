// OrderBuilder.java
package com.amazon.tests.dataseeding.builders;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestDataGenerator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builder for Order entities with Faker integration
 * Supports creating CreateOrderRequest DTOs for order creation
 *
 * Usage:
 * - OrderBuilder.anOrder().addItem(product, 2).build()
 * - OrderBuilder.anOrder().addMultipleItems(products).build()
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class OrderBuilder {

    private String namespace;
    private List<TestModels.OrderItemRequest> items;
    private String shippingAddress;
    private String notes;

    // Private constructor
    private OrderBuilder() {
        this.items = new ArrayList<>();
    }

    // ==========================================
    // STATIC FACTORY METHODS
    // ==========================================

    /**
     * Create a builder instance
     *
     * Example:
     * OrderBuilder.anOrder()
     *     .addItem(product, 2)
     *     .withShippingAddress("123 Main St")
     *     .build();
     */
    public static OrderBuilder anOrder() {
        return new OrderBuilder();
    }

    // ==========================================
    // BUILDER METHODS (Fluent API)
    // ==========================================

    /**
     * Set namespace for test isolation
     *
     * Example:
     * .withNamespace("test_12345")
     */
    public OrderBuilder withNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    /**
     * Add single item with specific quantity
     *
     * Example:
     * .addItem(product, 3)
     * // Adds 3 units of the product
     */
    public OrderBuilder addItem(TestModels.ProductResponse product, int quantity) {
        return addItem(
                product.getId(),
                product.getName(),
                product.getPrice(),
                quantity
        );
    }

    /**
     * Add single item with all details
     *
     * Example:
     * .addItem("prod-123", "Laptop", new BigDecimal("1299.99"), 1)
     */
    public OrderBuilder addItem(String productId, String productName,
                                BigDecimal unitPrice, int quantity) {
        items.add(TestModels.OrderItemRequest.builder()
                .productId(productId)
                .productName(productName)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .build());

        log.debug("Added item: {} x{} @ {}", productName, quantity, unitPrice);
        return this;
    }

    /**
     * Add item with random quantity (1-5)
     *
     * Example:
     * .addRandomItem(product)
     * // Quantity will be randomly between 1-5
     */
    public OrderBuilder addRandomItem(TestModels.ProductResponse product) {
        int randomQuantity = TestDataGenerator.generateOrderQuantity();
        return addItem(product, randomQuantity);
    }

    /**
     * Add item with custom quantity range
     *
     * Example:
     * .addItemWithQuantity(product, 2, 10)
     * // Quantity will be randomly between 2-10
     */
    public OrderBuilder addItemWithQuantity(TestModels.ProductResponse product,
                                            int minQuantity, int maxQuantity) {
        int quantity = TestDataGenerator.generateOrderQuantity(minQuantity, maxQuantity);
        return addItem(product, quantity);
    }

    /**
     * Add multiple items from product list with random quantities
     *
     * Example:
     * .addMultipleItems(List.of(product1, product2, product3))
     * // All 3 products added with random quantities
     */
    public OrderBuilder addMultipleItems(List<TestModels.ProductResponse> products) {
        products.forEach(this::addRandomItem);
        return this;
    }

    /**
     * Add specific number of random items from product list
     *
     * Example:
     * .addRandomItems(allProducts, 3)
     * // Randomly selects 3 products from the list
     */
    public OrderBuilder addRandomItems(List<TestModels.ProductResponse> products, int count) {
        if (products.isEmpty()) {
            throw new IllegalArgumentException("Product list cannot be empty");
        }

        List<TestModels.ProductResponse> shuffled = new ArrayList<>(products);
        java.util.Collections.shuffle(shuffled);

        int itemsToAdd = Math.min(count, shuffled.size());
        for (int i = 0; i < itemsToAdd; i++) {
            addRandomItem(shuffled.get(i));
        }

        return this;
    }

    /**
     * Add the first N products from the list
     *
     * Example:
     * .addFirstItems(products, 2)
     * // Adds first 2 products with random quantities
     */
    public OrderBuilder addFirstItems(List<TestModels.ProductResponse> products, int count) {
        int itemsToAdd = Math.min(count, products.size());
        for (int i = 0; i < itemsToAdd; i++) {
            addRandomItem(products.get(i));
        }
        return this;
    }

    /**
     * Set custom shipping address
     *
     * Example:
     * .withShippingAddress("123 Main Street, Seattle, WA 98101")
     */
    public OrderBuilder withShippingAddress(String address) {
        this.shippingAddress = address;
        return this;
    }

    /**
     * Use US shipping address
     *
     * Example:
     * .withUSAddress()
     * // Generates random US address
     */
    public OrderBuilder withUSAddress() {
        this.shippingAddress = TestDataGenerator.generateCompleteAddress();
        return this;
    }

    /**
     * Use Indian shipping address
     *
     * Example:
     * .withIndianAddress()
     * // Generates random Indian address
     */
    public OrderBuilder withIndianAddress() {
        this.shippingAddress = TestDataGenerator.generateIndianAddress();
        return this;
    }

    /**
     * Set custom order notes
     *
     * Example:
     * .withNotes("Gift wrap requested")
     */
    public OrderBuilder withNotes(String notes) {
        this.notes = notes;
        return this;
    }

    /**
     * Add gift note
     *
     * Example:
     * .withGiftNote()
     * // Notes: "Gift order - Handle with care"
     */
    public OrderBuilder withGiftNote() {
        this.notes = "Gift order - Handle with care";
        return this;
    }

    /**
     * Add priority shipping note
     *
     * Example:
     * .withPriorityShipping()
     * // Notes: "Priority shipping requested"
     */
    public OrderBuilder withPriorityShipping() {
        this.notes = "Priority shipping requested";
        return this;
    }

    /**
     * Add custom instruction
     *
     * Example:
     * .withInstruction("Leave at door")
     * // Notes: "Special instruction: Leave at door"
     */
    public OrderBuilder withInstruction(String instruction) {
        this.notes = "Special instruction: " + instruction;
        return this;
    }

    // ==========================================
    // PRESET CONFIGURATIONS
    // ==========================================

    /**
     * Create a small order (1-2 items)
     *
     * Example:
     * .smallOrder(products)
     */
    public OrderBuilder smallOrder(List<TestModels.ProductResponse> products) {
        return addRandomItems(products, TestDataGenerator.generateRandomNumber(1, 2));
    }

    /**
     * Create a medium order (3-5 items)
     *
     * Example:
     * .mediumOrder(products)
     */
    public OrderBuilder mediumOrder(List<TestModels.ProductResponse> products) {
        return addRandomItems(products, TestDataGenerator.generateRandomNumber(3, 5));
    }

    /**
     * Create a large order (6-10 items)
     *
     * Example:
     * .largeOrder(products)
     */
    public OrderBuilder largeOrder(List<TestModels.ProductResponse> products) {
        return addRandomItems(products, TestDataGenerator.generateRandomNumber(6, 10));
    }

    /**
     * Create a bulk order (same item, high quantity)
     *
     * Example:
     * .bulkOrder(product, 50)
     * // 50 units of the same product
     */
    public OrderBuilder bulkOrder(TestModels.ProductResponse product, int quantity) {
        return addItem(product, quantity);
    }

    // ==========================================
    // BUILD METHOD
    // ==========================================

    /**
     * Build the CreateOrderRequest
     *
     * @return CreateOrderRequest ready for API call
     * @throws IllegalStateException if no items added
     */
    public TestModels.CreateOrderRequest build() {
        if (items.isEmpty()) {
            throw new IllegalStateException("Order must have at least one item");
        }

        // Generate shipping address if not set
        String finalShippingAddress = shippingAddress != null ?
                shippingAddress : TestDataGenerator.generateCompleteAddress();

        // Generate notes if not set
        String finalNotes = notes != null ?
                notes : "Test order - " + UUID.randomUUID();

        // Add namespace to notes if provided
        if (namespace != null) {
            finalNotes = "[" + namespace + "] " + finalNotes;
        }

        log.debug("Building order: {} items, shipping to {}",
                items.size(), finalShippingAddress);

        return TestModels.CreateOrderRequest.builder()
                .items(items)
                .shippingAddress(finalShippingAddress)
               // .notes(finalNotes)
                .build();
    }

    // ==========================================
    // UTILITY METHODS
    // ==========================================

    /**
     * Get total number of items in order
     *
     * @return number of items
     */
    public int getItemCount() {
        return items.size();
    }

    /**
     * Get total quantity of all items
     *
     * @return total quantity
     */
    public int getTotalQuantity() {
        return items.stream()
                .mapToInt(TestModels.OrderItemRequest::getQuantity)
                .sum();
    }

    /**
     * Calculate order total
     *
     * @return total amount
     */
    public BigDecimal calculateTotal() {
        return items.stream()
                .map(item -> item.getUnitPrice()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Clear all items
     *
     * @return this builder
     */
    public OrderBuilder clearItems() {
        items.clear();
        return this;
    }
}
