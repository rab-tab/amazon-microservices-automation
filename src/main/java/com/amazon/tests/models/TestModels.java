package com.amazon.tests.models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Builder;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TestModels {

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RegisterRequest {
        private String username;
        private String email;
        private String password;
        private String firstName;
        private String lastName;
        private String phone;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LoginRequest {
        private String email;
        private String password;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthResponse {
        private String accessToken;
        private String tokenType;
        private long expiresIn;
        private UserResponse user;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UserResponse {
        private String id;
        private String username;
        private String email;
        private String firstName;
        private String lastName;
        private String phone;
        private String role;
        private String status;
        private String createdAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductRequest {
        private String name;
        private String description;
        private BigDecimal price;
        private Integer stockQuantity;
        private String categoryId;
        private String imageUrl;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProductResponse {
        private String id;
        private String name;
        private String description;
        private BigDecimal price;
        private Integer stockQuantity;
        private String categoryId;
        private String sellerId;
        private BigDecimal rating;
        private Integer reviewCount;
        private String status;
        private String createdAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CreateOrderRequest {
        private List<OrderItemRequest> items;
        private String shippingAddress;
        private String notes;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemRequest {
        private String productId;
        private Integer quantity;
        private BigDecimal unitPrice;
        private String productName;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderResponse {
        private String id;
        private String userId;
        private List<OrderItemResponse> items;
        private BigDecimal totalAmount;
        private String status;
        private String shippingAddress;
        private String paymentId;
        private String trackingNumber;
        @JsonDeserialize(using = LocalDateTimeToStringDeserializer.class)
        private String createdAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItemResponse {
        private String id;
        private String productId;
        private String productName;
        private Integer quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PagedProductResponse {
        private List<ProductResponse> products;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ErrorResponse {
        private int status;
        private String error;
        private String message;
        private String timestamp;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PagedOrderResponse {
        private List<OrderResponse> orders;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
    // Add this class inside TestModels.java (or as a separate file)
    public static class LocalDateTimeToStringDeserializer extends JsonDeserializer<String> {

        private static final DateTimeFormatter FORMATTER =
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

        @Override
        public String deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonToken token = p.currentToken();

            // Handle ISO string: "2024-01-15T10:30:45"
            if (token == JsonToken.VALUE_STRING) {
                return p.getText();
            }

            // Handle array: [2024, 1, 15, 10, 30, 45, 123456789]
            if (token == JsonToken.START_ARRAY) {
                int[] parts = p.readValueAs(int[].class);
                return String.format("%04d-%02d-%02dT%02d:%02d:%02d",
                        parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]);
            }

            // Handle object: {"year":2024,"monthValue":1,...}
            if (token == JsonToken.START_OBJECT) {
                LocalDateTime ldt = p.readValueAs(LocalDateTime.class);
                return ldt.format(FORMATTER);
            }

            return p.getText();
        }
    }
}
