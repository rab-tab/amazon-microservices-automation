package com.amazon.tests.utils;

import io.restassured.response.Response;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;

/**
 * Producer: Generates complete order test data
 *
 * Responsibilities:
 * 1. Create test users via API
 * 2. Create test products via API
 * 3. Assemble complete order data
 * 4. Put in queue for consumers
 */
public class OrderDataProducer implements Runnable {

    private final BlockingQueue<OrderCreationTestData> queue;
    private final int numberOfOrders;
    private final String producerId;
    private final String baseUrl;
    private final AtomicInteger producedCount;

    public OrderDataProducer(BlockingQueue<OrderCreationTestData> queue,
                             int numberOfOrders,
                             String producerId,
                             String baseUrl,
                             AtomicInteger producedCount) {
        this.queue = queue;
        this.numberOfOrders = numberOfOrders;
        this.producerId = producerId;
        this.baseUrl = baseUrl;
        this.producedCount = producedCount;
    }

    @Override
    public void run() {
        System.out.println("[" + producerId + "] Starting production of " + numberOfOrders + " orders");

        try {
            for (int i = 0; i < numberOfOrders; i++) {
                // Step 1: Create User
                UserData user = createTestUser(i);

                // Step 2: Create Product
                ProductData product = createTestProduct(i);

                // Step 3: Assemble Order Data
                OrderCreationTestData orderData = assembleOrderData(user, product);

                // Step 4: Put in queue
                queue.put(orderData);

                int count = producedCount.incrementAndGet();

                System.out.println(String.format(
                        "[%s] Produced %d/%d: User=%s, Product=%s",
                        producerId, count, numberOfOrders * getProducerCount(),
                        user.userId, product.sku
                ));

                // Small delay to simulate real-world data generation
                Thread.sleep(50);
            }

            System.out.println("[" + producerId + "] Completed production of " + numberOfOrders + " orders");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("[" + producerId + "] Producer interrupted");
        } catch (Exception e) {
            System.err.println("[" + producerId + "] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create test user via API
     */
    private UserData createTestUser(int index) {
        long timestamp = System.currentTimeMillis();
        String userId = String.format("user_%s_%d_%d", producerId, index, timestamp);
        String userName = "Test User " + userId;
        String email = String.format("%s@testdomain.com", userId);

        try {
            // Make API call to create user
            Response response = given()
                    .baseUri(baseUrl)
                    .contentType("application/json")
                    .body(String.format(
                            "{\"userId\":\"%s\",\"name\":\"%s\",\"email\":\"%s\",\"password\":\"Test@123\"}",
                            userId, userName, email
                    ))
                    .when()
                    .post("/api/users")
                    .then()
                    .extract().response();

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                System.out.println("[" + producerId + "] ✅ Created user: " + userId);
            } else {
                System.err.println("[" + producerId + "] ⚠️ User creation returned: " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("[" + producerId + "] ⚠️ User creation failed: " + e.getMessage());
        }

        return new UserData(userId, userName, email);
    }

    /**
     * Create test product via API
     */
    private ProductData createTestProduct(int index) {
        long timestamp = System.currentTimeMillis();
        String productSku = String.format("SKU-%s-%d-%d",
                producerId.replace("Producer-", ""),
                index,
                timestamp % 10000);
        String productName = "Test Product " + productSku;
        double price = 10.00 + (index % 90);  // Prices between $10-$100

        try {
            // Make API call to create product
            Response response = given()
                    .baseUri(baseUrl)
                    .contentType("application/json")
                    .body(String.format(
                            "{\"sku\":\"%s\",\"name\":\"%s\",\"price\":%.2f,\"stock\":100}",
                            productSku, productName, price
                    ))
                    .when()
                    .post("/api/products")
                    .then()
                    .extract().response();

            if (response.statusCode() == 201 || response.statusCode() == 200) {
                System.out.println("[" + producerId + "] ✅ Created product: " + productSku);
            } else {
                System.err.println("[" + producerId + "] ⚠️ Product creation returned: " + response.statusCode());
            }

        } catch (Exception e) {
            System.err.println("[" + producerId + "] ⚠️ Product creation failed: " + e.getMessage());
        }

        return new ProductData(productSku, productName, price);
    }

    /**
     * Assemble complete order test data
     */
    private OrderCreationTestData assembleOrderData(UserData user, ProductData product) {
        int quantity = ThreadLocalRandom.current().nextInt(1, 5);  // 1-5 items
        String address = generateAddress();

        return new OrderCreationTestData(
                user.userId,
                user.userName,
                user.email,
                product.sku,
                product.name,
                product.price,
                quantity,
                address
        );
    }

    /**
     * Generate random shipping address
     */
    private String generateAddress() {
        String[] streets = {"Main St", "Oak Ave", "Maple Dr", "Cedar Ln", "Pine Rd"};
        String[] cities = {"Seattle", "Portland", "San Francisco", "Denver", "Austin"};
        String[] states = {"WA", "OR", "CA", "CO", "TX"};

        int streetNum = ThreadLocalRandom.current().nextInt(100, 9999);
        String street = streets[ThreadLocalRandom.current().nextInt(streets.length)];
        String city = cities[ThreadLocalRandom.current().nextInt(cities.length)];
        String state = states[ThreadLocalRandom.current().nextInt(states.length)];
        int zip = ThreadLocalRandom.current().nextInt(10000, 99999);

        return String.format("%d %s, %s, %s %d", streetNum, street, city, state, zip);
    }

    private int getProducerCount() {
        // This would be passed in configuration, hardcoded for example
        return 3;
    }

    // Inner classes for structured data
    private static class UserData {
        String userId;
        String userName;
        String email;

        UserData(String userId, String userName, String email) {
            this.userId = userId;
            this.userName = userName;
            this.email = email;
        }
    }

    private static class ProductData {
        String sku;
        String name;
        double price;

        ProductData(String sku, String name, double price) {
            this.sku = sku;
            this.name = name;
            this.price = price;
        }
    }
}
