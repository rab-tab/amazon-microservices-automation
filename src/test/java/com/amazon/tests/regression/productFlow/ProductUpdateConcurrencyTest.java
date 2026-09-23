package com.amazon.tests.regression.productFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ProductService.updateProduct()'s behavior under concurrent updates,
 * and ProductRepository.updateStock()'s interaction with @Version now that
 * both are fixed. All tests intentionally restricted to CONFIRMED
 * ProductRequest builder fields (name, price) and the separate
 * updateStock() endpoint — TestModels.ProductRequest's support for
 * description/imageUrl/status has not been confirmed anywhere in this
 * project, so tests aren't built against guessed field names.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Product Catalog - Concurrency")
public class ProductUpdateConcurrencyTest extends BaseTest {

    // ══════════════════════════════════════════════════════════════
    // 1. ORIGINAL — different fields, 2 threads (the red test that
    //    originally proved the missing @Version bug)
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Two concurrent updates to DIFFERENT fields of the same product should NOT silently lose either change")
    @Story("Product Update - Lost Update Detection")
    @Severity(SeverityLevel.CRITICAL)
    public void testConcurrentUpdatesToDifferentFields_NeitherChangeShouldBeLost() throws Exception {
        logStep("TEST: Concurrent updates to different fields — proving/disproving the lost-update bug");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();

        TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, 100);
        String productId = product.getId();
        logStep("  ✓ Product created: " + productId);

        String updatedName = "Updated-By-Thread-A-" + System.nanoTime();
        BigDecimal updatedPrice = BigDecimal.valueOf(777.77);

        TestModels.ProductRequest nameOnlyUpdate = TestModels.ProductRequest.builder().name(updatedName).build();
        TestModels.ProductRequest priceOnlyUpdate = TestModels.ProductRequest.builder().price(updatedPrice).build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(2);
        ServiceResponse[] responses = new ServiceResponse[2];

        executor.submit(() -> {
            try {
                startGate.await();
                responses[0] = productApiClient.updateProductRaw(sellerAuth, productId, nameOnlyUpdate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endGate.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                responses[1] = productApiClient.updateProductRaw(sellerAuth, productId, priceOnlyUpdate);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endGate.countDown();
            }
        });

        startGate.countDown();
        endGate.await();
        executor.shutdown();

        assertThat(responses[0].getStatusCode()).as("Name-only update should report success").isEqualTo(200);
        assertThat(responses[1].getStatusCode()).as("Price-only update should report success").isEqualTo(200);

        TestModels.ProductResponse finalState = productApiClient.getProduct(productId);
        assertThat(finalState.getName())
                .as("Thread A's name change should NOT be silently lost")
                .isEqualTo(updatedName);
        assertThat(finalState.getPrice())
                .as("Thread B's price change should also not be lost")
                .isEqualByComparingTo(updatedPrice);

        logStep("✅ Neither concurrent update was silently lost");
    }

    // ══════════════════════════════════════════════════════════════
    // 2. SAME-FIELD RACE — legitimate last-writer-wins, must NOT error
    // ══════════════════════════════════════════════════════════════

    @Test(description = "3 concurrent updates to the SAME field (price) are a legitimate race — all should succeed, not error out")
    @Story("Product Update - Legitimate Same-Field Race")
    @Severity(SeverityLevel.NORMAL)
    public void testConcurrentUpdatesToSameField_LegitimateRaceStillSucceeds() throws Exception {
        logStep("TEST: 3 threads racing to set price to different values — must succeed cleanly, not error");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, 100);
        String productId = product.getId();

        List<BigDecimal> candidatePrices = List.of(
                BigDecimal.valueOf(11.11), BigDecimal.valueOf(22.22), BigDecimal.valueOf(33.33));

        int concurrentCount = candidatePrices.size();
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrentCount);
        List<ServiceResponse> responses = new CopyOnWriteArrayList<>();

        for (BigDecimal price : candidatePrices) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    TestModels.ProductRequest update = TestModels.ProductRequest.builder().price(price).build();
                    responses.add(productApiClient.updateProductRaw(sellerAuth, productId, update));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        endGate.await();
        executor.shutdown();

        long errorCount = responses.stream().filter(r -> r.getStatusCode() >= 400).count();
        assertThat(errorCount)
                .as("A legitimate same-field race should NOT error — @Version/retry should resolve it silently, " +
                        "the same way it resolves different-field collisions")
                .isZero();

        TestModels.ProductResponse finalState = productApiClient.getProduct(productId);
        logStep("  Final price: " + finalState.getPrice() + " (any of " + candidatePrices + " is a valid outcome)");

        assertThat(finalState.getPrice())
                .as("Final price should be exactly one of the three sent values — whichever legitimately won the race")
                .isIn(candidatePrices);

        logStep("✅ Same-field race resolved cleanly, no false-positive conflict errors");
    }

    // ══════════════════════════════════════════════════════════════
    // 3. updateStock() vs updateProduct() — proves the version-bump
    //    fix in the raw @Modifying query actually works
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Concurrent updateStock() and updateProduct() on the same product should NOT let either overwrite the other")
    @Story("Product Update - Cross-Path Version Consistency")
    @Severity(SeverityLevel.CRITICAL)
    public void testUpdateStockDoesNotGetRevertedByConcurrentProductUpdate() throws Exception {
        logStep("TEST: updateStock() (raw query) racing updateProductRaw() (entity save) on the same product");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

        int initialStock = 100;
        int stockDelta = -10; // matches updateStock()'s "+quantity" semantics, negative = decrement
        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, initialStock);
        String productId = product.getId();
        logStep("  ✓ Product created: " + productId + " (stock=" + initialStock + ")");

        String updatedName = "StockRaceName-" + System.nanoTime();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(2);
        Exception[] stockThreadError = new Exception[1];
        ServiceResponse[] nameUpdateResponse = new ServiceResponse[1];

        executor.submit(() -> {
            try {
                startGate.await();
                productApiClient.updateStock(productId, stockDelta); // throws if not 204
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                stockThreadError[0] = e;
            } finally {
                endGate.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startGate.await();
                TestModels.ProductRequest update = TestModels.ProductRequest.builder().name(updatedName).build();
                nameUpdateResponse[0] = productApiClient.updateProductRaw(sellerAuth, productId, update);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endGate.countDown();
            }
        });

        logStep("  🏁 Releasing both — stock decrement vs name update, simultaneously...");
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        assertThat(stockThreadError[0])
                .as("updateStock() should succeed with no exception")
                .isNull();
        assertThat(nameUpdateResponse[0].getStatusCode())
                .as("Concurrent name update should also succeed (via retry, if it collided)")
                .isEqualTo(200);

        TestModels.ProductResponse finalState = productApiClient.getProduct(productId);
        logStep("  Final state — stock: " + finalState.getStockQuantity() + ", name: \"" + finalState.getName() + "\"");

        assertThat(finalState.getStockQuantity())
                .as("Stock change from updateStock() should survive — this is exactly what the " +
                        "p.version = p.version + 1 fix in ProductRepository.updateStock() exists to guarantee. " +
                        "Without it, updateProductInternal()'s save() could silently revert the stock change " +
                        "back to its pre-decrement value, the same lost-update bug through a different door.")
                .isEqualTo(initialStock + stockDelta);
        assertThat(finalState.getName())
                .as("Name change should also survive")
                .isEqualTo(updatedName);

        logStep("✅ Stock and name changes both survived — updateStock()'s version bump correctly prevents cross-path data loss");
    }

    // ══════════════════════════════════════════════════════════════
    // 4. HIGH-CONTENTION STRESS — documents behavior under load rather
    //    than asserting a specific guaranteed exhaustion outcome
    // ══════════════════════════════════════════════════════════════

    /**
     * NOTE: genuinely forcing retry exhaustion (all 3 @Retryable attempts
     * failing) is inherently probabilistic via pure HTTP-level concurrency
     * — same limitation already flagged for pollForOrder()'s timeout path.
     * This test doesn't assert exhaustion WILL happen; it stresses the
     * system hard (10 threads on one field) and asserts that WHATEVER
     * happens is handled gracefully: every response is either a clean 200
     * (retry succeeded) or a clean 409 from GlobalExceptionHandler (retry
     * genuinely exhausted) — never an uncaught 500. If a 409 is ever
     * observed, it's logged explicitly so a real exhaustion case is visible
     * rather than silently blending into the passing runs.
     */
    @Test(description = "10-way contention on the same field — every response should be a clean 200 or 409, never an uncaught 500")
    @Story("Product Update - High Contention Stress")
    @Severity(SeverityLevel.NORMAL)
    public void testHighContentionStress_RetriesResolveOrFailGracefully() throws Exception {
        logStep("TEST: 10 threads racing the same field — documenting graceful resolution under real stress");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, 100);
        String productId = product.getId();

        int concurrentCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(concurrentCount);
        List<ServiceResponse> responses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrentCount; i++) {
            final int threadNum = i + 1;
            executor.submit(() -> {
                try {
                    startGate.await();
                    TestModels.ProductRequest update = TestModels.ProductRequest.builder()
                            .price(BigDecimal.valueOf(1000 + threadNum))
                            .build();
                    responses.add(productApiClient.updateProductRaw(sellerAuth, productId, update));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        logStep("  🏁 Releasing " + concurrentCount + " threads onto the same field...");
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        long successCount = responses.stream().filter(r -> r.getStatusCode() == 200).count();
        long conflictCount = responses.stream().filter(r -> r.getStatusCode() == 409).count();
        long unexpectedCount = responses.stream()
                .filter(r -> r.getStatusCode() != 200 && r.getStatusCode() != 409)
                .count();

        logStep("  200 (retry succeeded): " + successCount + " | 409 (genuinely exhausted): " + conflictCount +
                " | unexpected: " + unexpectedCount);

        if (conflictCount > 0) {
            logStep("  ℹ️ Observed " + conflictCount + " genuine retry-exhaustion case(s) under 10-way contention " +
                    "— handled gracefully as 409, not a crash.");
        }

        assertThat(unexpectedCount)
                .as("No response should ever be anything other than 200 or 409 — an uncaught 500 would mean " +
                        "GlobalExceptionHandler isn't catching something it should")
                .isZero();
        assertThat(successCount + conflictCount)
                .as("Every one of the " + concurrentCount + " requests should be accounted for")
                .isEqualTo((long) concurrentCount);

        logStep("✅ High contention resolved gracefully — no uncaught errors under real 10-way stress");
    }
}