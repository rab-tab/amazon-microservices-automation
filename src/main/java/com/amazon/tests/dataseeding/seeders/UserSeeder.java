// UserSeeder.java
package com.amazon.tests.dataseeding.seeders;

import com.amazon.tests.dataseeding.builders.UserBuilder;
import com.amazon.tests.dataseeding.core.BaseSeedingManager;
import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.models.TestModels;
import io.restassured.specification.RequestSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Seeder for User entities
 * Supports single and bulk user creation
 */
@Slf4j
public class UserSeeder extends BaseSeedingManager<UserSeeder.UserSeedResult> {

    private final int userCount;
    private final List<UserConfig> customConfigs;
    private final boolean parallel;

    private final List<TestModels.UserResponse> createdUsers = new CopyOnWriteArrayList<>();

    // Private constructor - use builder
    private UserSeeder(Builder builder) {
        super(builder.context);
        this.userCount = builder.userCount;
        this.customConfigs = builder.customConfigs;
        this.parallel = builder.parallel;
    }

    @Override
    protected UserSeedResult doSeed() throws Exception {
        List<TestModels.UserResponse> users;

        if (!customConfigs.isEmpty()) {
            // Seed with custom configurations
            users = seedCustomUsers();
        } else if (userCount > 1 && parallel) {
            // Bulk parallel seeding
            users = seedUsersParallel(userCount);
        } else {
            // Sequential seeding
            users = seedUsersSequential(userCount);
        }

        createdUsers.addAll(users);
        context.incrementStat("users_created");

        return UserSeedResult.builder()
                .namespace(getNamespace())
                .users(users)
                .count(users.size())
                .build();
    }

    private List<TestModels.UserResponse> seedCustomUsers() {
        List<TestModels.UserResponse> users = new ArrayList<>();

        for (UserConfig config : customConfigs) {
            TestModels.RegisterRequest request = config.builder
                    .withNamespace(getNamespace())
                    .build();

            TestModels.UserResponse user = createUser(request);
            users.add(user);
        }

        return users;
    }

    private List<TestModels.UserResponse> seedUsersSequential(int count) {
        List<TestModels.UserResponse> users = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            TestModels.RegisterRequest request = UserBuilder.randomUser(getNamespace());
            TestModels.UserResponse user = createUser(request);
            users.add(user);
        }

        return users;
    }

    private List<TestModels.UserResponse> seedUsersParallel(int count) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(count, Runtime.getRuntime().availableProcessors() * 2)
        );

        try {
            List<CompletableFuture<TestModels.UserResponse>> futures = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                CompletableFuture<TestModels.UserResponse> future = CompletableFuture.supplyAsync(
                        () -> {
                            TestModels.RegisterRequest request = UserBuilder.randomUser(getNamespace());
                            return createUser(request);
                        },
                        executor
                );
                futures.add(future);
            }

            // Wait for all to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

        } finally {
            executor.shutdown();
            //executor.awaitTermination(60, TimeUnit.SECONDS);
        }
    }

    private TestModels.UserResponse createUser(TestModels.RegisterRequest request) {
        log.debug("Creating user: {}", request.getEmail());

        // ✅ CORRECT: Get spec first
        RequestSpecification spec = context.getRestAssuredConfig().getUserServiceSpec();

        // Register via API
        TestModels.AuthResponse authResponse = context.getRestClient().post(
                context.getConfig().userServiceUrl() + "/api/auth/register",
                spec,
                request,
                TestModels.AuthResponse.class
        );

        TestModels.UserResponse user = authResponse.getUser();

        // Register cleanup
        context.registerCleanup(
                "User: " + user.getEmail(),
                () -> deleteUser(user.getId())
        );

        // Cache credentials for later use
        context.cache("user_password_" + user.getId(), request.getPassword());

        log.info("Created user: {} ({})", user.getEmail(), user.getId());
        return user;
    }

    @Override
    protected void doCleanup() {
        // Cleanup handled by context's cleanup tasks
        createdUsers.clear();
    }

    private void deleteUser(String userId) {
        try {
            context.getRestClient().delete(
                    context.getConfig().userServiceUrl() + "/api/users/" + userId
            );
            log.debug("Deleted user: {}", userId);
        } catch (Exception e) {
            log.warn("Failed to delete user: {}", userId, e);
        }
    }

    // Builder pattern

    public static Builder builder(SeedingContext context) {
        return new Builder(context);
    }

    public static class Builder {
        private final SeedingContext context;
        private int userCount = 1;
        private List<UserConfig> customConfigs = new ArrayList<>();
        private boolean parallel = false;

        private Builder(SeedingContext context) {
            this.context = context;
        }

        public Builder count(int count) {
            this.userCount = count;
            return this;
        }

        public Builder withConfig(java.util.function.Consumer<UserBuilder> config) {
            UserBuilder builder = UserBuilder.aUser();
            config.accept(builder);
            customConfigs.add(new UserConfig(builder));
            return this;
        }

        public Builder parallel() {
            this.parallel = true;
            return this;
        }

        public UserSeeder build() {
            return new UserSeeder(this);
        }
    }

    // Result and Config classes

    @lombok.Data
    @lombok.Builder
    public static class UserSeedResult {
        private String namespace;
        private List<TestModels.UserResponse> users;
        private int count;

        public TestModels.UserResponse getFirst() {
            return users.isEmpty() ? null : users.get(0);
        }

        public TestModels.UserResponse getRandom() {
            return users.isEmpty() ? null : users.get(new Random().nextInt(users.size()));
        }
    }

    @lombok.Value
    private static class UserConfig {
        UserBuilder builder;
    }
}