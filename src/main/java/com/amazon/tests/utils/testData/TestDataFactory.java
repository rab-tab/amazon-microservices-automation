package com.amazon.tests.utils.testData;

import com.amazon.tests.models.TestModels;
import com.github.javafaker.Faker;
import lombok.experimental.UtilityClass;
import org.apache.commons.math3.dfp.DfpField;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static com.amazon.tests.models.TestModels.*;

@UtilityClass
public class TestDataFactory {

    private static final Faker faker = new Faker();

    public static RegisterRequest createRandomUser() {
        return RegisterRequest.builder()
                .username(faker.name().username() + faker.number().digits(4))
                .email(faker.internet().emailAddress())
                .password("Test@" + faker.internet().password(6, 10))
                .firstName(faker.name().firstName())
                .lastName(faker.name().lastName())
                .phone("+1" + faker.number().digits(10))
                .build();
    }

    public static LoginRequest createLoginRequest(String email, String password) {
        return LoginRequest.builder()
                .email(email)
                .password(password)
                .build();
    }

    public static ProductRequest createRandomProduct() {
        return ProductRequest.builder()
                .name(faker.commerce().productName())
                .description(faker.lorem().paragraph())
                .price(BigDecimal.valueOf(faker.number().randomDouble(2, 1, 1000))
                        .setScale(2, RoundingMode.HALF_UP))
                .stockQuantity(faker.number().numberBetween(10, 500))
                .imageUrl("https://via.placeholder.com/400x400?text=" + faker.commerce().productName())
                .build();
    }

    public static ProductRequest createProductWithPrice(double price) {
        return ProductRequest.builder()
                .name(faker.commerce().productName())
                .description(faker.lorem().sentence())
                .price(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                .stockQuantity(100)
                .build();
    }

    public static CreateOrderRequest createOrderRequest(String productId,
                                                                    String productName,
                                                                    BigDecimal unitPrice) {
        return CreateOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder()
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

    public static CreateOrderRequest createMultiItemOrderRequest(
            List<OrderItemRequest> items) {
        return CreateOrderRequest.builder()
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
    public static TestModels.CreateOrderRequest.CreateOrderRequestBuilder defaultOrder(
            List<TestModels.ProductResponse> products) {
        List<TestModels.OrderItemRequest> items = products.stream()
                .map(p -> TestModels.OrderItemRequest.builder()
                        .productId(p.getId())
                        .productName(p.getName())
                        .unitPrice(p.getPrice())
                        .quantity(1)
                        .build())
                .toList();

        return TestModels.CreateOrderRequest.builder()
                .items(items)
                .shippingAddress("123 Amazon Way, Seattle, WA 98101");
    }

    public static ProductRequest createProductWithPriceAndStock(double price, int stockQuantity) {
        return ProductRequest.builder()
                .name(faker.commerce().productName())
                .description(faker.lorem().sentence())
                .price(BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP))
                .stockQuantity(stockQuantity)
                .build();
    }
}
