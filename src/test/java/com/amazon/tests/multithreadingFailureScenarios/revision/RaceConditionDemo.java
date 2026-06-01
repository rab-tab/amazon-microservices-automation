package com.amazon.tests.multithreadingFailureScenarios.revision;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Pure Java demo: ThreadLocal isolation vs shared state race condition
 * Run with: javac RaceConditionDemo.java && java RaceConditionDemo
 */
public class RaceConditionDemo {

    // ================================================================
    // SHARED STATE — the broken way
    // ================================================================
    static String sharedUserId;
    static String sharedOrderId;
    static String sharedToken;

    // ================================================================
    // THREADLOCAL — the correct way
    // ================================================================
    static ThreadLocal<UserContext> threadContext =
            ThreadLocal.withInitial(UserContext::new);

    static class UserContext {
        String userId;
        String orderId;
        String token;
    }

    // ================================================================
    // Fake "database" — order belongs to specific user
    // ================================================================
    static ConcurrentHashMap<String, String> orderOwnership = new ConcurrentHashMap<>();
    static AtomicInteger orderCounter = new AtomicInteger(0);
    static AtomicInteger userCounter  = new AtomicInteger(0);

    // ================================================================
    // Simulated service calls
    // ================================================================
    static String createUser() throws InterruptedException {
        Thread.sleep(10); // simulate network
        return "USER-" + userCounter.incrementAndGet();
    }

    static String createToken(String userId) {
        return "TOKEN-for-" + userId;
    }

    static String createOrder(String userId, String token) throws InterruptedException {
        Thread.sleep(5); // simulate network
        // Validate token matches user
        String expectedToken = "TOKEN-for-" + userId;
        if (!token.equals(expectedToken)) {
            return "ERROR: token mismatch! expected=" + expectedToken + " got=" + token;
        }
        String orderId = "ORD-" + orderCounter.incrementAndGet();
        orderOwnership.put(orderId, userId); // record who owns this order
        return orderId;
    }

    static String getOrder(String orderId, String requestingUserId) throws InterruptedException {
        Thread.sleep(5);
        String actualOwner = orderOwnership.get(orderId);
        if (actualOwner == null) return "ERROR: order not found";
        if (!actualOwner.equals(requestingUserId)) {
            return "WRONG ORDER! Requested by=" + requestingUserId
                    + " but order belongs to=" + actualOwner;
        }
        return "OK: order=" + orderId + " owner=" + requestingUserId;
    }

    // ================================================================
    // DEMO 1: WITHOUT ThreadLocal — race condition
    // ================================================================
    static void runWithoutThreadLocal() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("❌ WITHOUT ThreadLocal — RACE CONDITION DEMO");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                long tid = Thread.currentThread().getId();
                try {
                    // Step 1: Create user — both threads write to same variable
                    sharedUserId = createUser();
                    sharedToken  = createToken(sharedUserId);
                    System.out.println("[Thread-" + tid + "] Created user: " + sharedUserId);

                    Thread.sleep(2); // gap for other thread to overwrite

                    // Step 2: By now sharedUserId may have been overwritten!
                    String result = createOrder(sharedUserId, sharedToken);
                    sharedOrderId = result;
                    System.out.println("[Thread-" + tid + "] Order result: " + result
                            + " | sharedOrderId=" + sharedOrderId);

                    Thread.sleep(2);

                    // Step 3: Get order — sharedOrderId may be another thread's order!
                    String getResult = getOrder(sharedOrderId, sharedUserId);
                    System.out.println("[Thread-" + tid + "] GET order: " + getResult);

                } catch (Exception e) {
                    System.err.println("[Thread-" + tid + "] ERROR: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
    }

    // ================================================================
    // DEMO 2: WITH ThreadLocal — isolated, always correct
    // ================================================================
    static void runWithThreadLocal() throws InterruptedException {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("✅ WITH ThreadLocal — ISOLATED DEMO");
        System.out.println("=".repeat(60));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch latch = new CountDownLatch(2);

        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                long tid = Thread.currentThread().getId();
                try {
                    // Step 1: Create user — stored in THIS thread's context only
                    UserContext ctx = threadContext.get();
                    ctx.userId = createUser();
                    ctx.token  = createToken(ctx.userId);
                    System.out.println("[Thread-" + tid + "] Created user: " + ctx.userId);

                    Thread.sleep(2);

                    // Step 2: ctx.userId is ALWAYS this thread's user — no overwrite possible
                    ctx.orderId = createOrder(ctx.userId, ctx.token);
                    System.out.println("[Thread-" + tid + "] Order result: " + ctx.orderId);

                    Thread.sleep(2);

                    // Step 3: ctx.orderId is always THIS thread's order
                    String getResult = getOrder(ctx.orderId, ctx.userId);
                    System.out.println("[Thread-" + tid + "] GET order: " + getResult);

                } catch (Exception e) {
                    System.err.println("[Thread-" + tid + "] ERROR: " + e.getMessage());
                } finally {
                    threadContext.remove(); // prevent memory leak
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();
    }

    // ================================================================
    // MAIN
    // ================================================================
    public static void main(String[] args) throws InterruptedException {
        System.out.println("Running 5 times each to show non-determinism...\n");

        System.out.println("▶▶▶ WITHOUT ThreadLocal (run 5x — notice inconsistent results)");
        for (int i = 1; i <= 5; i++) {
            System.out.println("\n--- Run " + i + " ---");
            orderOwnership.clear();
            runWithoutThreadLocal();
        }

        System.out.println("\n\n▶▶▶ WITH ThreadLocal (run 5x — always correct)");
        for (int i = 1; i <= 5; i++) {
            System.out.println("\n--- Run " + i + " ---");
            runWithThreadLocal();
        }
    }
}