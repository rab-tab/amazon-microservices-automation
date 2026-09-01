package com.amazon.tests.regression.orderCreationFlow.sharding;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.transport.ServiceResponse;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.UUID;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

@Slf4j
@Test(groups = "sharding")
public class ShardRoutingResilienceTests extends AbstractShardTest {

    @AfterMethod(alwaysRun = true)
    public void resetToxics() throws Exception {
        // Every test must leave shards healthy for the next one —
        // a chaos test that doesn't clean up poisons the whole suite.
        toxiproxy.resetAll();
    }

    // ---------- 9. One shard down, others unaffected ----------

    @Test(description = "Taking one shard down should fail only that shard's requests; other shards remain healthy",enabled = false)
    public void testCreateOrder_OneShardDown_OtherShardsUnaffected() throws Exception {

        int downShard = 0;
        int healthyShard = 1;
        toxiproxy.takeDown(downShard);

        String userIdOnDownShard = shardKeyResolver.generateUserIdForShard(downShard);
        String userIdOnHealthyShard = shardKeyResolver.generateUserIdForShard(healthyShard);

        logStep("Shard " + downShard + " taken down — verifying isolation");

        ServiceResponse downShardResponse = orderApiClient.createOrderWithFault(
                userIdOnDownShard, UUID.randomUUID().toString(),
                buildOrderRequest(), null);

        assertTrue(downShardResponse.getStatusCode() >= 500 && downShardResponse.getStatusCode() < 600,
                "Down-shard request should fail with a server-side error, got " + downShardResponse.getStatusCode());

        TestModels.OrderResponse healthyOrder = orderApiClient.createOrder(
                userIdOnHealthyShard, UUID.randomUUID().toString(), sharedProduct);

        assertNotNull(healthyOrder.getId(), "Healthy-shard order should succeed while another shard is down");

        logStep("✅ Shard isolation confirmed — down shard failed, healthy shard unaffected");
    }

    // ---------- 10. Latency spike, timeout handled gracefully ----------

    @Test(description = "A latency spike on one shard should fail clean (timeout/error), not hang the request indefinitely")
    public void testCreateOrder_ShardLatencySpike_TimeoutHandledGracefully() throws Exception {

        int slowShard = 2;
        toxiproxy.injectLatency(slowShard, 10_000, 500); // 10s latency; socketTimeout is configured at 8s

        String userIdOnSlowShard = shardKeyResolver.generateUserIdForShard(slowShard);

        logStep("Latency injected on shard " + slowShard + " — verifying graceful timeout");

        long start = System.nanoTime();
        ServiceResponse response = orderApiClient.createOrderWithFault(
                userIdOnSlowShard, UUID.randomUUID().toString(),
                buildOrderRequest(), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 10_000,
                "Request took " + elapsedMs + "ms — appears to have waited out the full injected latency rather than timing out on the configured 8s socketTimeout");
        assertTrue(response.getStatusCode() >= 500,
                "Slow-shard request should fail with a server error once timeout is hit, got " + response.getStatusCode());

        logStep("✅ Timeout handled gracefully in " + elapsedMs + "ms");
    }

    private TestModels.CreateOrderRequest buildOrderRequest() {
        return TestModels.CreateOrderRequest.builder()
                .items(List.of(TestModels.OrderItemRequest.builder()
                        .productId(sharedProduct.get(0).getId())
                        .productName(sharedProduct.get(0).getName())
                        .unitPrice(sharedProduct.get(0).getPrice())
                        .quantity(1)
                        .build()))
                .shippingAddress("123 Amazon Way, Seattle, WA 98101")
                .build();
    }
}