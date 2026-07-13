package com.amazon.tests.commonmodels;


import com.amazon.tests.commonmodels.enums.ProductStatus;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class ProductDto {

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateRequest {
        @NotBlank(message = "Product name is required")
        @Size(max = 200)
        private String name;

        private String description;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        private BigDecimal price;

        @NotNull(message = "Stock quantity is required")
        @Min(value = 0, message = "Stock quantity cannot be negative")
        private Integer stockQuantity;

        private UUID categoryId;
        private String imageUrl;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateRequest {
        private String name;
        private String description;
        @DecimalMin(value = "0.01")
        private BigDecimal price;
        @Min(0)
        private Integer stockQuantity;
        private UUID categoryId;
        private String imageUrl;
        private ProductStatus status;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductResponse {
        private UUID id;
        private String name;
        private String description;
        private BigDecimal price;
        private Integer stockQuantity;
        private UUID categoryId;
        private UUID sellerId;
        private String imageUrl;
        private BigDecimal rating;
        private Integer reviewCount;
        private ProductStatus status;
        private LocalDateTime createdAt;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
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
    public static class StockUpdateRequest {
        @NotNull
        private UUID productId;
        @NotNull
        private Integer quantity;  // positive = restock, negative = deduct
    }
}

