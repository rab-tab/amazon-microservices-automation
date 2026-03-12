package com.amazon.tests.utils;

import com.amazon.tests.models.TestModels;
import com.github.javafaker.Faker;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@UtilityClass
public class TestDataFactory {

    private static final Faker faker = new Faker();

    public static TestModels.RegisterRequest createRandomUser() {
        return TestModels.RegisterRequest.builder()
                .username(faker.name().username() + faker.number().digits(4))
                .email(faker.internet().emailAddress())
                .password("Test@" + faker.internet().password(6, 10))
                .firstName(faker.name().firstName())
                .lastName(faker.name().lastName())
                .phone("+1" + faker.number().digits(10))
                .build();
    }

    public static TestModels.LoginRequest createLoginRequest(String email, String password) {
        return TestModels.LoginRequest.builder()
                .email(email)
                .password(password)
                .build();
    }

    public static TestModels.ProductRequest createRandomProduct() {
        return TestModels.ProductRequest.builder()
                .name(faker.commerce().productName())
                .description(faker.lorem().paragraph())
                .price(BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000))
                        .setScale(2, RoundingMode.HALF_UP))
                .stockQuantity(faker.number().numberBetween(10, 500))
                .imageUrl("https://via.placeholder.com/400x400?text=" + faker.commerce().productName())
                .build();
    }

    public static TestModels.ProductRequest createProductWithPrice(double price) {
        return TestModels.ProductRequest.builder()
                .name(faker.commerce().productName())
                .description(faker.lorem().sentence())
                .price(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                .stockQuantity(100)
                .build();
    }

    public static TestModels.CreateOrderRequest createOrderRequest(String productId,
                                                                    String productName,
                                                                    BigDecimal unitPrice) {
        return TestModels.CreateOrderRequest.builder()
                .items(List.of(
                        TestModels.OrderItemRequest.builder()
                                .productId(productId)
                                .productName(productName)
                                .quantity(faker.number().numberBetween(1, 5))
                                .unitPrice(unitPrice)
                                .build()
                ))
                .shippingAddress(faker.address().fullAddress())
                .notes("Test order - " + UUID.randomUUID())
                .build();
    }

    public static TestModels.CreateOrderRequest createMultiItemOrderRequest(
            List<TestModels.OrderItemRequest> items) {
        return TestModels.CreateOrderRequest.builder()
                .items(items)
                .shippingAddress(faker.address().fullAddress())
                .notes("Multi-item test order")
                .build();
    }

    public static String randomEmail() {
        return faker.internet().emailAddress();
    }

    public static String randomPassword() {
        return "Test@" + faker.internet().password(6, 10) + "1";
    }
}
