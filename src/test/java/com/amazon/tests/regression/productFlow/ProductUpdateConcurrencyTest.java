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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests ProductService.updateProduct()'s behavior under concurrent updates.
 *
 * ⭐ CONFIRMED (via direct review of Product.java): Product has NO @Version
 * column, unlike Order. updateProduct() does a plain read-modify-save with
 * zero optimistic-locking protection and zero exception handling around the
 * save.
 *
 * THIS TEST IS DELIBERATELY EXPECTED TO FAIL against the current code —
 * same "red test first" workflow as OrderCancellationConcurrencyTest. It
 * demonstrates a genuine silent lost-update bug, not a coverage gap. Once
 * @Version + retry is added to Product (mirroring cancelOrder()'s fix), this
 * should pass.
 *
 * THE BUG, PRECISELY: two concurrent requests each update a DIFFERENT field
 * of the same product. Both read the product before either writes. Both
 * return 200 — no conflict is ever detected, because there's no version
 * column to detect it with. Whichever save() commits SECOND persists its
 * own change correctly, but ALSO silently overwrites the FIRST request's
 * change — because it re-saves the whole entity state it loaded, which
 * still holds the pre-update value for the field it never touched. The
 * first caller is told their update succeeded (a real 200) — it was
 * actually discarded moments later.
 */
@Slf4j
@Epic("Amazon Microservices")
@Feature("Product Catalog - Concurrency")
public class ProductUpdateConcurrencyTest extends BaseTest {

    @Test(description = "Two concurrent updates to DIFFERENT fields of the same product should NOT silently lose either change — proves the missing @Version bug")
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
        logStep("  ✓ Product created: " + productId + " (name=\"" + product.getName() + "\", price=" + product.getPrice() + ")");

        String updatedName = "Updated-By-Thread-A-" + System.nanoTime();
        BigDecimal updatedPrice = BigDecimal.valueOf(777.77);

        TestModels.ProductRequest nameOnlyUpdate = TestModels.ProductRequest.builder()
                .name(updatedName)
                .build();
        TestModels.ProductRequest priceOnlyUpdate = TestModels.ProductRequest.builder()
                .price(updatedPrice)
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(2);
        ServiceResponse[] responses = new ServiceResponse[2];

        executor.submit(() -> {
            try {
                startGate.await();
                responses[0] = productApiClient.updateProductRaw(sellerAuth, productId, nameOnlyUpdate);
                log.info("Thread A (name-only) returned status {}", responses[0].getStatusCode());
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
                log.info("Thread B (price-only) returned status {}", responses[1].getStatusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                endGate.countDown();
            }
        });

        logStep("  🏁 Releasing both concurrent updates simultaneously (name-only vs price-only)...");
        startGate.countDown();
        endGate.await();
        executor.shutdown();

        assertThat(responses[0].getStatusCode())
                .as("Name-only update should report success — this is exactly the danger: it reports " +
                        "success even when about to be silently overwritten a moment later")
                .isEqualTo(200);
        assertThat(responses[1].getStatusCode())
                .as("Price-only update should report success")
                .isEqualTo(200);

        TestModels.ProductResponse finalState = productApiClient.getProduct(productId);
        logStep("  Final state — name: \"" + finalState.getName() + "\", price: " + finalState.getPrice());

        assertThat(finalState.getName())
                .as("Thread A's name change should NOT be silently lost, even though Thread B's update " +
                        "committed afterward and never touched the name field. If this fails, it's direct " +
                        "proof of the lost-update bug: Product has no @Version, so Thread B's save() " +
                        "persisted its own stale (pre-Thread-A) copy of the name field, reverting A's " +
                        "already-committed, already-200'd change.")
                .isEqualTo(updatedName);

        assertThat(finalState.getPrice())
                .as("Thread B's price change should also not be lost")
                .isEqualByComparingTo(updatedPrice);

        logStep("✅ Neither concurrent update was silently lost — no lost-update bug present");
    }
}