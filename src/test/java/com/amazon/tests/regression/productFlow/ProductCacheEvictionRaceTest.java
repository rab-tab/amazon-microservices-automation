package com.amazon.tests.regression.productFlow;

import com.amazon.tests.BaseTest;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import io.qameta.allure.*;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the cache-aside eviction race on ProductService's
 * @Cacheable(getProductById) / @CacheEvict(updateProduct) pair — a
 * DIFFERENT bug class from everything in ProductUpdateConcurrencyTest.
 * That class covers DB/entity-level lost updates (now closed by @Version);
 * this one covers a CACHE-layer read/write race that @Version has no effect
 * on at all, since the cache sits in front of the DB entirely.
 *
 * THE RACE: a concurrent getProductById() can start a cache-miss DB read
 * BEFORE an update commits, then finish AFTER the update's @CacheEvict has
 * already fired — populating the cache with the now-stale value it read.
 * The cache is then permanently wrong (correct in DB, wrong in cache) until
 * something else evicts it.
 *
 * ⚠️ HONESTY NOTE, same category as other "prove a specific race window"
 * tests in this project (pollForOrder()'s timeout, the high-contention
 * stress test): this can't force the exact interleaving on demand over
 * plain HTTP. It hammers the timing window repeatedly with concurrent
 * readers during a write and checks for the STALE-FOREVER symptom — if it
 * never reproduces in N attempts, that's evidence the delayed-double-delete
 * mitigation (or just favorable timing) is working, NOT proof the race is
 * impossible.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Product Catalog - Cache Consistency")
public class ProductCacheEvictionRaceTest extends BaseTest {

    @Test(description = "Cache should never permanently serve a stale value after a confirmed update — stresses the read/write timing window repeatedly")
    @Story("Product Cache - Eviction Race")
    @Severity(SeverityLevel.NORMAL)
    public void testCacheNeverPermanentlyServesStaleValueAfterUpdate() throws Exception {
        logStep("TEST: Repeated concurrent-read-during-write attempts to catch a permanently stale cache entry");

        PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                .registerSeller()
                .execute();
        TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
        ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

        TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, 100);
        String productId = product.getId();
        logStep("  ✓ Product created: " + productId + " (initial price: " + product.getPrice() + ")");

        // Warm the cache with the initial value first, so every attempt
        // below is racing an actual cache-population event, not a cold miss.
        productApiClient.getProduct(productId);

        int attempts = 10;
        int readersPerAttempt = 8;
        int permanentlyStaleCount = 0;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            BigDecimal newPrice = BigDecimal.valueOf(1000 + attempt);
            TestModels.ProductRequest update = TestModels.ProductRequest.builder().price(newPrice).build();

            ExecutorService executor = Executors.newFixedThreadPool(readersPerAttempt + 1);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(readersPerAttempt + 1);
            List<BigDecimal> observedPrices = new CopyOnWriteArrayList<>();

            // Writer
            executor.submit(() -> {
                try {
                    startGate.await();
                    productApiClient.updateProductRaw(sellerAuth, productId, update);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });

            // Concurrent readers, racing the writer
            for (int r = 0; r < readersPerAttempt; r++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        TestModels.ProductResponse read = productApiClient.getProduct(productId);
                        observedPrices.add(read.getPrice());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // A read racing a write can legitimately see either the
                        // old or new value transiently — not itself a failure.
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            endGate.await();
            executor.shutdown();

            // Give the delayed-double-delete's second eviction time to fire
            // (DELAYED_EVICT_MS + margin) before checking for PERMANENT staleness.
            Thread.sleep(800);

            TestModels.ProductResponse finalRead = productApiClient.getProduct(productId);
            boolean permanentlyStale = finalRead.getPrice().compareTo(newPrice) != 0;

            if (permanentlyStale) {
                permanentlyStaleCount++;
                log.error("Attempt {}: cache STILL stale after settling — expected {}, got {}",
                        attempt, newPrice, finalRead.getPrice());
            }

            logStep("  Attempt " + attempt + "/" + attempts + " — final price: " + finalRead.getPrice()
                    + (permanentlyStale ? " ❌ STALE" : " ✓"));
        }

        logStep("  Permanently stale outcomes: " + permanentlyStaleCount + "/" + attempts);

        assertThat(permanentlyStaleCount)
                .as("Cache should not permanently serve a stale value after settling — " +
                        "if this fails, the delayed-double-delete mitigation isn't fully closing " +
                        "the eviction race window under this level of contention")
                .isZero();

        logStep("✅ No permanently stale cache reads observed across " + attempts + " attempts");
    }

    // ══════════════════════════════════════════════════════════════
    // COUNTERPART — same race, via deleteProduct() instead of update.
    // A fresh product is created PER ATTEMPT here (unlike the price test
    // above) since delete only makes semantic sense once per product —
    // deleting an already-deleted product doesn't meaningfully re-exercise
    // the race the way re-updating a price does.
    // ══════════════════════════════════════════════════════════════

    @Test(description = "Cache should never permanently serve a stale (pre-delete) product after a confirmed delete — same race, via deleteProduct()")
    @Story("Product Cache - Eviction Race")
    @Severity(SeverityLevel.NORMAL)
    public void testCacheNeverPermanentlyServesStaleValueAfterDelete() throws Exception {
        logStep("TEST: Repeated concurrent-read-during-delete attempts to catch a permanently stale (still-ACTIVE) cache entry");

        int attempts = 5;
        int readersPerAttempt = 8;
        int permanentlyStaleCount = 0;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            PurchaseResult purchase = PurchaseWorkflow.start(context.getExecutor(), authStrategy)
                    .registerSeller()
                    .execute();
            TestModels.AuthResponse sellerAuth = purchase.getSellerAuth();
            ProductApiClient productApiClient = new ProductApiClient(context.getExecutor());

            TestModels.ProductResponse product = productApiClient.createProduct(sellerAuth, 19.99, 100);
            String productId = product.getId();

            // Warm the cache before racing the delete, same as the price test.
            productApiClient.getProduct(productId);

            ExecutorService executor = Executors.newFixedThreadPool(readersPerAttempt + 1);
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch endGate = new CountDownLatch(readersPerAttempt + 1);

            // Deleter
            executor.submit(() -> {
                try {
                    startGate.await();
                    productApiClient.deleteProduct(sellerAuth, productId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });

            // Concurrent readers, racing the delete
            for (int r = 0; r < readersPerAttempt; r++) {
                executor.submit(() -> {
                    try {
                        startGate.await();
                        productApiClient.getProduct(productId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        // A read racing a delete can legitimately see either
                        // ACTIVE or DISCONTINUED transiently — not a failure.
                    } finally {
                        endGate.countDown();
                    }
                });
            }

            startGate.countDown();
            endGate.await();
            executor.shutdown();

            // Give the delayed-double-delete's second eviction time to fire.
            Thread.sleep(800);

            TestModels.ProductResponse finalRead = productApiClient.getProduct(productId);
            boolean permanentlyStale = !"DISCONTINUED".equals(finalRead.getStatus());

            if (permanentlyStale) {
                permanentlyStaleCount++;
                log.error("Attempt {}: cache STILL shows product as {} after settling — expected DISCONTINUED",
                        attempt, finalRead.getStatus());
            }

            logStep("  Attempt " + attempt + "/" + attempts + " — final status: " + finalRead.getStatus()
                    + (permanentlyStale ? " ❌ STALE" : " ✓"));
        }

        logStep("  Permanently stale outcomes: " + permanentlyStaleCount + "/" + attempts);

        assertThat(permanentlyStaleCount)
                .as("Cache should not permanently serve a stale (still-ACTIVE) product after a confirmed delete")
                .isZero();

        logStep("✅ No permanently stale cache reads observed across " + attempts + " delete attempts");
    }
}