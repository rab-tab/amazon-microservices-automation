package com.amazon.tests.commonmodels;


import com.amazon.tests.commonmodels.enums.PaymentMethod;
import com.amazon.tests.commonmodels.enums.PaymentStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public class PaymentDto {

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentRequest {
        @NotNull
        private UUID orderId;

        @NotNull
        @DecimalMin("0.01")
        private BigDecimal amount;

        @Builder.Default
        private String currency = "USD";

        @NotNull
        private PaymentMethod paymentMethod;
    }

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResponse {
        private UUID id;
        private UUID orderId;
        private UUID userId;
        private BigDecimal amount;
        private String currency;
        private PaymentStatus status;
        private PaymentMethod paymentMethod;
        private String transactionId;
        private String failureReason;
        private LocalDateTime createdAt;
    }
}

