package com.amazon.tests.regression.orderCreationFlow.sharding;

import com.amazon.tests.BaseTest;
import com.amazon.tests.config.sharding.*;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.apiClients.AuthApiClient;
import com.amazon.tests.utils.apiClients.OrderApiClient;
import com.amazon.tests.utils.apiClients.ProductApiClient;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeSuite;

import java.util.List;

/**
 * Shared setup for all Partition Routing test classes (functional, perf,
 * resilience). Environment is started ONCE at suite scope and shared
 * across all three subclasses — assumes they run together under one
 * TestNG <suite>. If that assumption ever changes (classes run
 * independently), this needs to move to @BeforeClass/@AfterClass instead.
 */
public abstract class AbstractShardTest extends BaseTest {

    private static ShardTestEnvironment environment;

    protected static TestShardKeyResolver shardKeyResolver;
    protected static ShardAwareOrderDao shardAwareOrderDao;
    protected static ToxiproxyShardController toxiproxy;

    protected AuthApiClient authApiClient;
    protected ProductApiClient productApiClient;
    protected OrderApiClient orderApiClient;

    protected TestModels.AuthResponse sellerData;
    protected List<TestModels.ProductResponse> sharedProduct;

    @BeforeSuite(alwaysRun = true)
    public static synchronized void startSharedEnvironment() throws Exception {
        if (environment != null) return;

        environment = ShardTestEnvironment.resolve();
        environment.start();

        ShardTopologyConfig topology = environment.getTopology();
        shardKeyResolver = new TestShardKeyResolver(topology.getShardCount());
        shardAwareOrderDao = new ShardAwareOrderDao(topology);
        toxiproxy = environment.getToxiproxyController();
    }

    @AfterSuite(alwaysRun = true)
    public static synchronized void stopSharedEnvironment() throws Exception {
        if (shardAwareOrderDao != null) shardAwareOrderDao.closeAll();
        if (environment != null) environment.stop();
    }

    @BeforeClass(alwaysRun = true)
    public void setupSharedTestData() {
        authApiClient = new AuthApiClient(executor);
        productApiClient = new ProductApiClient(executor);
        orderApiClient = new OrderApiClient(authStrategy, executor);

        sellerData = authApiClient.registerSeller();
        sharedProduct = productApiClient.createProducts(sellerData, 1);
    }
}