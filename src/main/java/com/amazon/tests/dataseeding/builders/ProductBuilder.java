// ProductBuilder.java
package com.amazon.tests.dataseeding.builders;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.TestDataGenerator;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;


/**
 * Builder for Product entities with Faker integration
 * Supports creating ProductRequest DTOs for product creation
 *
 * Usage:
 * - ProductBuilder.randomProduct() - Quick random product
 * - ProductBuilder.aProduct().cheap().highStock().build() - Custom product
 *
 * @author Amazon Automation Team
 */
@Slf4j
public class ProductBuilder {

    private String namespace;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stockQuantity;
    private String categoryId;
    private String imageUrl;

    // Private constructor
    private ProductBuilder() {
    }

    // ==========================================
    // STATIC FACTORY METHODS
    // ==========================================

    /**
     * Create a builder instance
     *
     * Example:
     * ProductBuilder.aProduct()
     *     .withName("Gaming Laptop")
     *     .withPrice(1299.99)
     *     .build();
     */
    public static ProductBuilder aProduct() {
        return new ProductBuilder();
    }

    /**
     * Create a random product with all fields generated
     *
     * Example:
     * ProductRequest product = ProductBuilder.randomProduct();
     */
    public static TestModels.ProductRequest randomProduct() {
        return new ProductBuilder().build();
    }

    /**
     * Create a random product with namespace for test isolation
     *
     * Example:
     * ProductRequest product = ProductBuilder.randomProduct("test_12345");
     * // Name: test_12345_Ergonomic Steel Mouse
     */
    public static TestModels.ProductRequest randomProduct(String namespace) {
        return new ProductBuilder()
                .withNamespace(namespace)
                .build();
    }

    /**
     * Create a cheap product (price: $1 - $20)
     *
     * Example:
     * ProductRequest product = ProductBuilder.cheapProduct();
     */
    public static TestModels.ProductRequest cheapProduct() {
        return new ProductBuilder()
                .cheap()
                .build();
    }

    /**
     * Create an expensive product (price: $100 - $1000)
     *
     * Example:
     * ProductRequest product = ProductBuilder.expensiveProduct();
     */
    public static TestModels.ProductRequest expensiveProduct() {
        return new ProductBuilder()
                .expensive()
                .build();
    }

    // ==========================================
    // BUILDER METHODS (Fluent API)
    // ==========================================

    /**
     * Set namespace for test isolation
     * Namespace will be prefixed to product name
     *
     * Example:
     * .withNamespace("test_12345")
     * // Results in: test_12345_ProductName
     */
    public ProductBuilder withNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    /**
     * Set custom product name
     *
     * Example:
     * .withName("Gaming Laptop")
     */
    public ProductBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Set custom description
     *
     * Example:
     * .withDescription("High-performance gaming laptop with RTX 4090")
     */
    public ProductBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * Set price as BigDecimal
     *
     * Example:
     * .withPrice(new BigDecimal("1299.99"))
     */
    public ProductBuilder withPrice(BigDecimal price) {
        this.price = price;
        return this;
    }

    /**
     * Set price as double (converted to BigDecimal)
     *
     * Example:
     * .withPrice(1299.99)
     */
    public ProductBuilder withPrice(double price) {
        this.price = BigDecimal.valueOf(price).setScale(2, BigDecimal.ROUND_HALF_UP);
        return this;
    }

    /**
     * Set price in range (min, max)
     *
     * Example:
     * .inPriceRange(100.0, 500.0)
     */
    public ProductBuilder inPriceRange(double min, double max) {
        double randomPrice = TestDataGenerator.generateProductPrice(min, max);
        this.price = BigDecimal.valueOf(randomPrice).setScale(2, BigDecimal.ROUND_HALF_UP);
        return this;
    }

    /**
     * Set as cheap product ($1 - $20)
     *
     * Example:
     * .cheap()
     */
    public ProductBuilder cheap() {
        return inPriceRange(1.0, 20.0);
    }

    /**
     * Set as medium-priced product ($20 - $100)
     *
     * Example:
     * .mediumPrice()
     */
    public ProductBuilder mediumPrice() {
        return inPriceRange(20.0, 100.0);
    }

    /**
     * Set as expensive product ($100 - $1000)
     *
     * Example:
     * .expensive()
     */
    public ProductBuilder expensive() {
        return inPriceRange(100.0, 1000.0);
    }

    /**
     * Set as premium product ($1000 - $5000)
     *
     * Example:
     * .premium()
     */
    public ProductBuilder premium() {
        return inPriceRange(1000.0, 5000.0);
    }

    /**
     * Set stock quantity
     *
     * Example:
     * .withStockQuantity(100)
     */
    public ProductBuilder withStockQuantity(int quantity) {
        this.stockQuantity = quantity;
        return this;
    }

    /**
     * Set as low stock (1 - 10 units)
     *
     * Example:
     * .lowStock()
     */
    public ProductBuilder lowStock() {
        this.stockQuantity = TestDataGenerator.generateRandomNumber(1, 10);
        return this;
    }

    /**
     * Set as medium stock (10 - 100 units)
     *
     * Example:
     * .mediumStock()
     */
    public ProductBuilder mediumStock() {
        this.stockQuantity = TestDataGenerator.generateRandomNumber(10, 100);
        return this;
    }

    /**
     * Set as high stock (100 - 1000 units)
     *
     * Example:
     * .highStock()
     */
    public ProductBuilder highStock() {
        this.stockQuantity = TestDataGenerator.generateRandomNumber(100, 1000);
        return this;
    }

    /**
     * Set as out of stock (0 units)
     *
     * Example:
     * .outOfStock()
     */
    public ProductBuilder outOfStock() {
        this.stockQuantity = 0;
        return this;
    }

    /**
     * Set category ID
     *
     * Example:
     * .withCategoryId("electronics-123")
     */
    public ProductBuilder withCategoryId(String categoryId) {
        this.categoryId = categoryId;
        return this;
    }

    /**
     * Set image URL
     *
     * Example:
     * .withImageUrl("https://example.com/product.jpg")
     */
    public ProductBuilder withImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
        return this;
    }

    /**
     * Generate placeholder image URL
     *
     * Example:
     * .withPlaceholderImage()
     * // Results in: https://via.placeholder.com/400x400?text=ProductName
     */
    public ProductBuilder withPlaceholderImage() {
        String productName = name != null ? name : "Product";
        this.imageUrl = "https://via.placeholder.com/400x400?text=" +
                productName.replace(" ", "+");
        return this;
    }

    // ==========================================
    // PRESET CONFIGURATIONS
    // ==========================================

    /**
     * Create electronics product configuration
     *
     * Example:
     * .asElectronics()
     * // Medium to expensive, medium to high stock
     */
    public ProductBuilder asElectronics() {
        return this
                .inPriceRange(50.0, 2000.0)
                .mediumStock()
                .withPlaceholderImage();
    }

    /**
     * Create clothing product configuration
     *
     * Example:
     * .asClothing()
     * // Cheap to medium, high stock
     */
    public ProductBuilder asClothing() {
        return this
                .inPriceRange(10.0, 100.0)
                .highStock()
                .withPlaceholderImage();
    }

    /**
     * Create book product configuration
     *
     * Example:
     * .asBook()
     * // Cheap, medium stock
     */
    public ProductBuilder asBook() {
        return this
                .inPriceRange(5.0, 50.0)
                .mediumStock()
                .withPlaceholderImage();
    }

    /**
     * Create grocery product configuration
     *
     * Example:
     * .asGrocery()
     * // Very cheap, very high stock
     */
    public ProductBuilder asGrocery() {
        return this
                .inPriceRange(1.0, 30.0)
                .withStockQuantity(TestDataGenerator.generateRandomNumber(500, 2000))
                .withPlaceholderImage();
    }

    // ==========================================
    // BUILD METHOD
    // ==========================================

    /**
     * Build the ProductRequest with all configured values
     * Missing fields are filled with random data from Faker
     *
     * @return ProductRequest ready for API call
     */
    public TestModels.ProductRequest build() {
        // Generate final name with namespace if provided
        String finalName = buildName();

        // Generate SKU for image URL
        String sku = TestDataGenerator.generateProductSku();

        // Generate other fields with defaults if not set
        String finalDescription = description != null ?
                description : TestDataGenerator.generateProductDescription();

        BigDecimal finalPrice = price != null ?
                price : BigDecimal.valueOf(TestDataGenerator.generateProductPrice())
                .setScale(2, BigDecimal.ROUND_HALF_UP);

        Integer finalStock = stockQuantity != null ?
                stockQuantity : TestDataGenerator.generateRandomNumber(10, 500);

        String finalImageUrl = imageUrl != null ?
                imageUrl : "https://via.placeholder.com/400x400?text=" + sku;

        log.debug("Building product: name={}, price={}, stock={}",
                finalName, finalPrice, finalStock);

        return TestModels.ProductRequest.builder()
                .name(finalName)
                .description(finalDescription)
                .price(finalPrice)
                .stockQuantity(finalStock)
                .categoryId(categoryId)  // Can be null
                .imageUrl(finalImageUrl)
                .build();
    }

    // ==========================================
    // HELPER METHODS
    // ==========================================

    private String buildName() {
        String baseName = name != null ? name : TestDataGenerator.generateProductName();

        // Add namespace prefix if provided
        if (namespace != null && !baseName.contains(namespace)) {
            return namespace + "_" + baseName;
        }

        return baseName;
    }
}