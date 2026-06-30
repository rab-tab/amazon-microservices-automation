package com.amazon.tests.config;

import com.amazon.tests.dataseeding.core.SeedingContext;
import com.amazon.tests.dataseeding.seeders.ProductSeeder;
import com.amazon.tests.dataseeding.seeders.UserSeeder;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.kafka.KafkaTestProducer;

public class TestEnvironmentBuilder {
    private final SeedingContext context;

    private final TestEnvironment env =
            new TestEnvironment();

    private TestEnvironmentBuilder(SeedingContext context) {
        this.context = context;
    }

    public static TestEnvironmentBuilder builder(
            SeedingContext context) {

        return new TestEnvironmentBuilder(context);
    }

    public TestEnvironment build() {
        return env;
    }

    public TestEnvironmentBuilder withUser()
            throws Exception {

        TestModels.UserResponse user = UserSeeder.builder(context)
                .count(1)
                .build()
                .seed()
                .getFirst();

        env.setUser(user);

        env.setUserToken(
                context.getCached(
                        "user_token_" + user.getId(),
                        String.class));

        return this;
    }

    public TestEnvironmentBuilder withProduct()
            throws Exception {

        TestModels.ProductResponse product =
                ProductSeeder.builder(context)
                        .count(1)
                        .highStock()
                        .build()
                        .seed()
                        .getFirst();

        env.setProduct(product);

        return this;
    }
    public TestEnvironmentBuilder withKafkaConsumer(
            String topic) {

        KafkaTestConsumer consumer =
                new KafkaTestConsumer(topic);

        consumer.seekToEnd();
        env.getConsumers().put(topic,consumer);

        return this;
    }
    public TestEnvironmentBuilder withKafkaProducer() {

        env.setKafkaProducer(
                new KafkaTestProducer());

        return this;
    }
    public TestEnvironmentBuilder waitForPropagation(
            long millis) {

        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return this;
    }
    public TestEnvironmentBuilder withDLQTopics() {

       // createDLQTopicIfNotExists("order.events.DLQ");
       // createDLQTopicIfNotExists("payment.result.DLQ");

        return this;
    }
}
