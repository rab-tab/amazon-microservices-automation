package com.amazon.tests.tests.load;

import com.amazon.tests.utils.OrderCreationTestData;
import com.amazon.tests.utils.OrderDataProducer;
import com.amazon.tests.utils.OrderTestConsumer;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * Order Creation Load Test using Producer-Consumer Pattern
 *
 * Architecture:
 * - Producers: Create users and products, generate order test data
 * - Queue: Thread-safe buffer for order test data
 * - Consumers: Execute order creation tests and validate
 *
 * Configuration:
 * - 3 Producer threads
 * - 20 Consumer threads
 * - Queue capacity: 100
 * - Total orders: 1000 (configurable)
 */
public class OrderCreationLoadTest {

    // Test Configuration
    private static final String BASE_URL = "http://localhost:8080";  // Update with your API URL
    private static final int TOTAL_ORDERS = 1000;
    private static final int PRODUCER_COUNT = 3;
    private static final int CONSUMER_COUNT = 20;
    private static final int QUEUE_CAPACITY = 100;

    // Shared components
    private BlockingQueue<OrderCreationTestData> queue;
    private ExecutorService producerPool;
    private ExecutorService consumerPool;

    // Metrics
    private AtomicInteger producedCount;
    private AtomicInteger processedCount;
    private AtomicInteger successCount;
    private AtomicInteger failureCount;
    private ConcurrentHashMap<String, String> createdOrders;

    private long startTime;
    private long endTime;

    @BeforeClass
    public void setup() {
        System.out.println("=".repeat(70));
        System.out.println("ORDER CREATION LOAD TEST - PRODUCER-CONSUMER PATTERN");
        System.out.println("=".repeat(70));
        System.out.println("Configuration:");
        System.out.println("  Total Orders: " + TOTAL_ORDERS);
        System.out.println("  Producers: " + PRODUCER_COUNT);
        System.out.println("  Consumers: " + CONSUMER_COUNT);
        System.out.println("  Queue Capacity: " + QUEUE_CAPACITY);
        System.out.println("  Base URL: " + BASE_URL);
        System.out.println("=".repeat(70));

        // Initialize shared components
        queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
        createdOrders = new ConcurrentHashMap<>();

        // Initialize metrics
        producedCount = new AtomicInteger(0);
        processedCount = new AtomicInteger(0);
        successCount = new AtomicInteger(0);
        failureCount = new AtomicInteger(0);

        // Initialize thread pools
        producerPool = Executors.newFixedThreadPool(PRODUCER_COUNT);
        consumerPool = Executors.newFixedThreadPool(CONSUMER_COUNT);
    }

    @Test
    public void testOrderCreationAtScale() throws InterruptedException {

        startTime = System.currentTimeMillis();

        // Step 1: Start Producers
        System.out.println("\n[MAIN] Starting " + PRODUCER_COUNT + " producers...");
        startProducers();

        // Step 2: Start Consumers
        System.out.println("[MAIN] Starting " + CONSUMER_COUNT + " consumers...");
        startConsumers();

        // Step 3: Wait for producers to finish
        System.out.println("[MAIN] Waiting for producers to complete...");
        producerPool.shutdown();
        boolean producersFinished = true;
        //boolean producersFinished = producerPool.awaitTermination(30, TimeUnit.MINUTES);

        if (!producersFinished) {
            System.err.println("[MAIN] ⚠️ Producers did not finish in time!");
        } else {
            System.out.println("[MAIN] ✅ All producers completed");
            System.out.println("[MAIN] Total data produced: " + producedCount.get());
        }

        // Step 4: Send poison pills to consumers
        System.out.println("[MAIN] Sending shutdown signals to consumers...");
        sendPoisonPills();

        // Step 5: Wait for consumers to finish
        System.out.println("[MAIN] Waiting for consumers to complete...");
        consumerPool.shutdown();
        //boolean consumersFinished = consumerPool.awaitTermination(30, TimeUnit.MINUTES);
        boolean consumersFinished = false;

        if (!consumersFinished) {
            System.err.println("[MAIN] ⚠️ Consumers did not finish in time!");
        } else {
            System.out.println("[MAIN] ✅ All consumers completed");
        }

        endTime = System.currentTimeMillis();

        // Step 6: Print results
        printResults();

        // Step 7: Assertions
        performAssertions();
    }

    /**
     * Start producer threads
     */
    private void startProducers() {
        int ordersPerProducer = TOTAL_ORDERS / PRODUCER_COUNT;
        int remainingOrders = TOTAL_ORDERS % PRODUCER_COUNT;

        for (int i = 0; i < PRODUCER_COUNT; i++) {
            // Distribute remaining orders to first producers
            int orderCount = ordersPerProducer + (i < remainingOrders ? 1 : 0);

            String producerId = "Producer-" + (i + 1);

            OrderDataProducer producer = new OrderDataProducer(
                    queue,
                    orderCount,
                    producerId,
                    BASE_URL,
                    producedCount
            );

            producerPool.submit(producer);

            System.out.println(String.format(
                    "[MAIN] Started %s (will produce %d orders)",
                    producerId, orderCount
            ));
        }
    }

    /**
     * Start consumer threads
     */
    private void startConsumers() {
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            String consumerId = "Consumer-" + (i + 1);

            OrderTestConsumer consumer = new OrderTestConsumer(
                    queue,
                    consumerId,
                    BASE_URL,
                    successCount,
                    failureCount,
                    processedCount,
                    createdOrders
            );

            consumerPool.submit(consumer);

            System.out.println(String.format(
                    "[MAIN] Started %s",
                    consumerId
            ));
        }
    }

    /**
     * Send poison pills to stop consumers
     */
    private void sendPoisonPills() throws InterruptedException {
        for (int i = 0; i < CONSUMER_COUNT; i++) {
            queue.put(OrderCreationTestData.POISON_PILL);
        }
        System.out.println("[MAIN] Sent " + CONSUMER_COUNT + " poison pills");
    }

    /**
     * Print test results
     */
    private void printResults() {
        long durationMs = endTime - startTime;
        double durationSec = durationMs / 1000.0;
        double throughput = successCount.get() / durationSec;

        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST RESULTS");
        System.out.println("=".repeat(70));

        System.out.println("\nExecution Metrics:");
        System.out.println("  Total Orders Attempted: " + TOTAL_ORDERS);
        System.out.println("  Data Produced: " + producedCount.get());
        System.out.println("  Tests Executed: " + processedCount.get());
        System.out.println("  Successful: " + successCount.get() + " ✅");
        System.out.println("  Failed: " + failureCount.get() + " ❌");

        double successRate = (successCount.get() * 100.0) / Math.max(processedCount.get(), 1);
        System.out.println("  Success Rate: " + String.format("%.2f%%", successRate));

        System.out.println("\nPerformance Metrics:");
        System.out.println("  Total Duration: " + String.format("%.2f seconds", durationSec));
        System.out.println("  Throughput: " + String.format("%.2f orders/second", throughput));
        System.out.println("  Average Time per Order: " + String.format("%.2f ms",
                durationMs / (double) Math.max(processedCount.get(), 1)));

        System.out.println("\nQueue Metrics:");
        System.out.println("  Final Queue Size: " + queue.size());
        System.out.println("  Queue Capacity: " + QUEUE_CAPACITY);

        System.out.println("\nOrders Created:");
        System.out.println("  Total Unique Orders: " + createdOrders.size());

        // Sample of created orders (first 5)
        System.out.println("\nSample Orders:");
        createdOrders.entrySet().stream()
                .limit(5)
                .forEach(entry ->
                        System.out.println("    OrderID: " + entry.getKey() + " | User: " + entry.getValue())
                );

        System.out.println("\n" + "=".repeat(70));
    }

    /**
     * Perform test assertions
     */
    private void performAssertions() {
        // Assert all orders were produced
        assertEquals(producedCount.get(), TOTAL_ORDERS,
                "All orders should be produced");

        // Assert all orders were processed
        assertEquals(processedCount.get(), TOTAL_ORDERS,
                "All orders should be processed");

        // Assert success rate is acceptable (>95%)
        double successRate = (successCount.get() * 100.0) / processedCount.get();
        assertTrue(successRate >= 95.0,
                "Success rate should be at least 95%, but was " +
                        String.format("%.2f%%", successRate));

        // Assert queue is empty
        assertEquals(queue.size(), 0, "Queue should be empty after processing");

        System.out.println("\n✅ All assertions passed!");
    }

    @AfterClass
    public void cleanup() {
        System.out.println("\n[MAIN] Starting cleanup...");

        // Cleanup created orders (optional - comment out if you want to keep data)
        if (createdOrders != null && !createdOrders.isEmpty()) {
            System.out.println("[MAIN] Cleaning up " + createdOrders.size() + " created orders...");
            // Implement cleanup logic here if needed
            // deleteOrders(createdOrders.keySet());
        }

        // Ensure thread pools are shutdown
        if (producerPool != null && !producerPool.isShutdown()) {
            producerPool.shutdownNow();
        }

        if (consumerPool != null && !consumerPool.isShutdown()) {
            consumerPool.shutdownNow();
        }

        System.out.println("[MAIN] Cleanup completed");
    }
}
