package com.amazon.tests.regression.orderCreationFlow.sharding;


import com.amazon.tests.BaseTest;
import com.amazon.tests.config.sharding.ShardAwareOrderDao;
import com.amazon.tests.config.sharding.ShardTopologyConfig;
import com.amazon.tests.config.sharding.TestShardKeyResolver;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import com.amazon.tests.workflows.PurchaseResult;
import com.amazon.tests.workflows.PurchaseWorkflow;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;

import static org.testng.Assert.assertEquals;

/**
 * Functional shard-routing verification for order creation.
 *
 * Reads/writes are verified directly against each shard's Postgres
 * instance via ShardAwareOrderDao — never through the app's own read
 * path, since a broadcast/merge read across all shards can still
 * return correct data even when a WRITE landed on the wrong shard.
 *
 * Requires the "sharded" profile / docker-compose target running
 * (order-service + order-db-shard0..3). Not part of default regression —
 * tag/group accordingly in the suite XML.
 */
@Slf4j
public class ShardRoutingFunctionalTests extends BaseTest {

    private AuthApiClient authApiClient;
    private ProductApiClient productApiClient;
    private OrderApiClient orderApiClient;

    private ShardAwareOrderDao shardAwareOrderDao;
    private TestShardKeyResolver shardKeyResolver;

    private TestModels.AuthResponse sellerData;
    private List<TestModels.ProductResponse> sharedProduct;

    @BeforeClass
    public void setup() {
        authApiClient = new AuthApiClient(executor);
        productApiClient = new ProductApiClient(executor);
        orderApiClient = new OrderApiClient(authStrategy, executor);

        ShardTopologyConfig topology = ShardTopologyConfig.load("shard-topology.properties");
        shardKeyResolver = new TestShardKeyResolver(topology.getShardCount());
        shardAwareOrderDao = new ShardAwareOrderDao(topology);

        sellerData = authApiClient.registerSeller();
        // Reused across shard-targeted order creations in test 2.
        // ASSUMPTION: default stock quantity from
        // TestDataFactory.createProductWithPrice() is high enough to absorb
        // one order per shard — confirm if this test flakes on stock depletion.
        sharedProduct = productApiClient.createProducts(sellerData, 1);
    }

    @AfterClass
    public void tearDown() {
        if (shardAwareOrderDao != null) {
            shardAwareOrderDao.closeAll();
        }
    }

    // ==========================================
    // 1. Basic routing correctness — via standard PurchaseWorkflow flow
    // ==========================================

    @Test(description = "Order should land on exactly the shard predicted by the resolver for the workflow-assigned userId", priority = 1)
    public void testCreateOrder_RoutesToExpectedShard() {

        logStep("Executing: shard routing check via standard purchase flow");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct(1)
                .browseProducts()
                .viewProduct()
                .createOrder()
                .execute();

        TestModels.OrderResponse order = purchase.getOrder();
        String userId = order.getUserId();
        String orderId = order.getId();

        logStep("Validating: order routed to correct shard for userId=" + userId);

        int expectedShard = shardKeyResolver.expectedShardFor(userId);
        List<Integer> actualShards = shardAwareOrderDao.findAllShardsContaining(orderId);

        assertEquals(actualShards.size(), 1,
                "Order " + orderId + " should exist on exactly one shard");
        assertEquals(actualShards.get(0), Integer.valueOf(expectedShard),
                "Order should be on the shard predicted by userId.hashCode() % shardCount");

        logStep("✅ Shard routing verified for userId=" + userId);
    }

    // ==========================================
    // 2. Every shard deliberately targeted and confirmed reachable
    // ==========================================

    @Test(description = "A userId deliberately targeted at each shard should route there and only there", priority = 2)
    public void testCreateOrder_EveryShardIsReachable() {

        for (int targetShard = 0; targetShard < shardKeyResolver.getShardCount(); targetShard++) {

            String userId = shardKeyResolver.generateUserIdForShard(targetShard);
            String idempotencyKey = UUID.randomUUID().toString();

            logStep("Executing: order for userId=" + userId + " targeting shard " + targetShard);

            TestModels.OrderResponse order = orderApiClient.createOrder(userId, idempotencyKey, sharedProduct);

            List<Integer> actualShards = shardAwareOrderDao.findAllShardsContaining(order.getId());

            assertEquals(actualShards.size(), 1,
                    "Order " + order.getId() + " should exist on exactly one shard");
            assertEquals(actualShards.get(0), Integer.valueOf(targetShard),
                    "userId=" + userId + " should route to shard " + targetShard);

            logStep("✅ Shard " + targetShard + " reachable and correctly routed");
        }
    }

    // ==========================================
    // 3. Write-then-read consistency — same shard, via standard workflow
    // ==========================================

    @Test(description = "Immediate read after write should return the same order", priority = 3)
    public void testGetOrder_AfterCreate_ReadsFromSameShardAsWrite() {

        logStep("Executing: write-then-read consistency check");

        PurchaseResult purchase = PurchaseWorkflow.start(executor, authStrategy)
                .registerCustomer()
                .loginCustomer()
                .registerSeller()
                .createProduct(1)
                .browseProducts()
                .viewProduct()
                .createOrder()
                .execute();

        TestModels.OrderResponse order = purchase.getOrder();
        String userId = order.getUserId();
        String orderId = order.getId();

        logStep("Validating: read-after-write for orderId=" + orderId);

        TestModels.OrderResponse fetched = orderApiClient.getOrder(authStrategy.getToken(), userId, orderId);

        assertEquals(fetched.getId(), orderId);
        assertEquals(fetched.getUserId(), userId);

        logStep("✅ Read-after-write consistency verified for orderId=" + orderId);
    }

    // ==========================================
    // 4. Pure routing-formula unit check — no HTTP, no DB
    // ==========================================

    @Test(description = "Shard resolution must be deterministic for a given userId", priority = 4)
    public void testShardRouter_ConsistentHashing_SameKeyAlwaysSameShard() {

        String userId = UUID.randomUUID().toString();
        int firstResolution = shardKeyResolver.expectedShardFor(userId);

        for (int i = 0; i < 50; i++) {
            assertEquals(shardKeyResolver.expectedShardFor(userId), firstResolution,
                    "Resolution must be stable across repeated calls (iteration " + i + ")");
        }
    }

    // ==========================================
    // 5. Invalid/unroutable key handling — createOrderWithFault already
    //    returns a raw ServiceResponse, no client changes needed
    // ==========================================

    @Test(description = "A null userId should surface as 400 somewhere in the chain, not a raw 500", priority = 5)
    public void testCreateOrder_NullUserId_Returns4xxNotServerError() {

        logStep("Executing: null userId negative-path check");

        TestModels.CreateOrderRequest request = TestModels.CreateOrderRequest.builder()
                .items(List.of(TestModels.OrderItemRequest.builder()
                        .productId(sharedProduct.get(0).getId())
                        .productName(sharedProduct.get(0).getName())
                        .unitPrice(sharedProduct.get(0).getPrice())
                        .quantity(1)
                        .build()))
                .shippingAddress("123 Amazon Way, Seattle, WA 98101")
                .build();

        ServiceResponse response = orderApiClient.createOrderWithFault(
                null, UUID.randomUUID().toString(), request, null);

        assertEquals(response.getStatusCode(), 400,
                "Null userId must not surface as a 500. Actual body: " + response.getBody());

        logStep("✅ Null userId correctly rejected with 400. Body: " + response.getBody());
    }
}