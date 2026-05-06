// ProductSeeder.java
package com.amazon.tests.dataseeding.seeders;

import com.amazon.tests.dataseeding.core.*;
import com.amazon.tests.dataseeding.builders.ProductBuilder;
import com.amazon.tests.models.TestModels;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;

/**
 * Seeder for Product entities
 */
@Slf4j
public class ProductSeeder extends BaseSeedingManager<ProductSeeder.ProductSeedResult> {

    private final int productCount;
    private final List<ProductConfig> customConfigs;
    private final PriceRange priceRange;
    private final StockLevel stockLevel;
    private final boolean parallel;
    private final String sellerToken;

    private final List<TestModels.ProductResponse> createdProducts = new CopyOnWriteArrayList<>();

    private ProductSeeder(Builder builder) {
        super(builder.context);
        this.productCount = builder.productCount;
        this.customConfigs = builder.customConfigs;
        this.priceRange = builder.priceRange;
        this.stockLevel = builder.stockLevel;
        this.parallel = builder.parallel;
        this.sellerToken = builder.sellerToken;
    }

    @Override
    protected ProductSeedResult doSeed() throws Exception {
        List<TestModels.ProductResponse> products;

        if (!customConfigs.isEmpty()) {
            products = seedCustomProducts();
        } else if (productCount > 1 && parallel) {
            products = seedProductsParallel(productCount);
        } else {
            products = seedProductsSequential(productCount);
        }

        createdProducts.addAll(products);
        context.incrementStat("products_created");

        return ProductSeedResult.builder()
                .namespace(getNamespace())
                .products(products)
                .count(products.size())
                .build();
    }

    private List<TestModels.ProductResponse> seedCustomProducts() {
        List<TestModels.ProductResponse> products = new ArrayList<>();

        for (ProductConfig config : customConfigs) {
            TestModels.ProductRequest request = config.builder
                    .withNamespace(getNamespace())
                    .build();

            TestModels.ProductResponse product = createProduct(request);
            products.add(product);
        }

        return products;
    }

    private List<TestModels.ProductResponse> seedProductsSequential(int count) {
        List<TestModels.ProductResponse> products = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            ProductBuilder builder = ProductBuilder.aProduct()
                    .withNamespace(getNamespace());

            applyDefaultConfig(builder);

            TestModels.ProductRequest request = builder.build();
            TestModels.ProductResponse product = createProduct(request);
            products.add(product);
        }

        return products;
    }

    private List<TestModels.ProductResponse> seedProductsParallel(int count) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(count, Runtime.getRuntime().availableProcessors() * 2)
        );

        try {
            List<CompletableFuture<TestModels.ProductResponse>> futures = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                CompletableFuture<TestModels.ProductResponse> future = CompletableFuture.supplyAsync(
                        () -> {
                            ProductBuilder builder = ProductBuilder.aProduct()
                                    .withNamespace(getNamespace());
                            applyDefaultConfig(builder);
                            return createProduct(builder.build());
                        },
                        executor
                );
                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

        } finally {
            executor.shutdown();
           // executor.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private void applyDefaultConfig(ProductBuilder builder) {
        // Apply price range
        if (priceRange != null) {
            switch (priceRange) {
                case CHEAP -> builder.cheap();
                case MEDIUM -> builder.mediumPrice();
                case EXPENSIVE -> builder.expensive();
            }
        }

        // Apply stock level
        if (stockLevel != null) {
            switch (stockLevel) {
                case LOW -> builder.lowStock();
                case HIGH -> builder.highStock();
            }
        }
    }

    private TestModels.ProductResponse createProduct(TestModels.ProductRequest request) {
        log.debug("Creating product: {}", request.getName());

        Map<String, String> headers = new HashMap<>();
        if (sellerToken != null) {
            headers.put("Authorization", "Bearer " + sellerToken);
        }

        TestModels.ProductResponse product = context.getRestClient().post(
                context.getConfig().productServiceUrl() + "/api/products",
                request,
                TestModels.ProductResponse.class,
                headers
        );

        // Register cleanup
        context.registerCleanup(
                "Product: " + product.getName(),
                () -> deleteProduct(product.getId())
        );

        log.info("Created product: {} ({})", product.getName(), product.getId());
        return product;
    }

    @Override
    protected void doCleanup() {
        createdProducts.clear();
    }

    private void deleteProduct(String productId) {
        try {
            Map<String, String> headers = new HashMap<>();
            if (sellerToken != null) {
                headers.put("Authorization", "Bearer " + sellerToken);
            }

            context.getRestClient().delete(
                    context.getConfig().productServiceUrl() + "/api/products/" + productId,
                    headers
            );
            log.debug("Deleted product: {}", productId);
        } catch (Exception e) {
            log.warn("Failed to delete product: {}", productId, e);
        }
    }

    // Builder

    public static Builder builder(SeedingContext context) {
        return new Builder(context);
    }

    public static class Builder {
        private final SeedingContext context;
        private int productCount = 1;
        private List<ProductConfig> customConfigs = new ArrayList<>();
        private PriceRange priceRange;
        private StockLevel stockLevel;
        private boolean parallel = false;
        private String sellerToken;

        private Builder(SeedingContext context) {
            this.context = context;
        }

        public Builder count(int count) {
            this.productCount = count;
            return this;
        }

        public Builder withConfig(java.util.function.Consumer<ProductBuilder> config) {
            ProductBuilder builder = ProductBuilder.aProduct();
            config.accept(builder);
            customConfigs.add(new ProductConfig(builder));
            return this;
        }

        public Builder cheap() {
            this.priceRange = PriceRange.CHEAP;
            return this;
        }

        public Builder mediumPrice() {
            this.priceRange = PriceRange.MEDIUM;
            return this;
        }

        public Builder expensive() {
            this.priceRange = PriceRange.EXPENSIVE;
            return this;
        }

        public Builder lowStock() {
            this.stockLevel = StockLevel.LOW;
            return this;
        }

        public Builder highStock() {
            this.stockLevel = StockLevel.HIGH;
            return this;
        }

        public Builder parallel() {
            this.parallel = true;
            return this;
        }

        public Builder withSellerToken(String token) {
            this.sellerToken = token;
            return this;
        }

        public ProductSeeder build() {
            return new ProductSeeder(this);
        }
    }

    // Enums and Result classes

    public enum PriceRange {
        CHEAP, MEDIUM, EXPENSIVE
    }

    public enum StockLevel {
        LOW, HIGH
    }

    @lombok.Data
    @lombok.Builder
    public static class ProductSeedResult {
        private String namespace;
        private List<TestModels.ProductResponse> products;
        private int count;

        public TestModels.ProductResponse getFirst() {
            return products.isEmpty() ? null : products.get(0);
        }

        public TestModels.ProductResponse getRandom() {
            return products.isEmpty() ? null : products.get(new Random().nextInt(products.size()));
        }

        public List<TestModels.ProductResponse> getCheap() {
            return products.stream()
                    .filter(p -> p.getPrice().doubleValue() < 20)
                    .toList();
        }

        public List<TestModels.ProductResponse> getExpensive() {
            return products.stream()
                    .filter(p -> p.getPrice().doubleValue() >= 100)
                    .toList();
        }
    }

    @lombok.Value
    private static class ProductConfig {
        ProductBuilder builder;
    }
}