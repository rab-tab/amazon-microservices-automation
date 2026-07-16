package com.amazon.tests.validators;



import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.ProductFacade;
import com.amazon.tests.workflows.PurchaseResult;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

public class ProductValidator {

    private final ProductFacade productFacade = new ProductFacade();

    /**
     * Fetch latest product details from Product Service
     */
    private TestModels.ProductResponse getProduct(PurchaseResult purchase) {
        return productFacade.getProduct(
                purchase.getProduct().getId());
    }

    /**
     * Verify product is ACTIVE
     */
    public void verifyProductIsActive(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getStatus())
                .isEqualTo("ACTIVE");
    }

    public void verifyProductCreated(PurchaseResult purchase) {

        verifyProductIsActive(purchase);
        verifyProductPrice(purchase);
        verifyProductBelongsToSeller(purchase);
    }

    /**
     * Verify product name
     */
    public void verifyProductName(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getName())
                .isEqualTo(purchase.getProduct().getName());
    }

    /**
     * Verify product description
     */
    public void verifyProductDescription(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getDescription())
                .isEqualTo(purchase.getProduct().getDescription());
    }

    /**
     * Verify product price
     */
    public void verifyProductPrice(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getPrice())
                .isEqualByComparingTo(
                        purchase.getProduct().getPrice());
    }

    /**
     * Verify seller owns the product
     */
    public void verifyProductBelongsToSeller(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getSellerId())
                .isEqualTo(
                        purchase.getSellerAuth().getUser().getId());
    }

    /**
     * Verify stock is available
     */
    public void verifyStockAvailable(PurchaseResult purchase) {

        assertThat(getProduct(purchase).getStockQuantity())
                .isGreaterThan(0);
    }

    /**
     * Verify category
     */
    public void verifyCategory(PurchaseResult purchase, String expectedCategoryId) {

        assertThat(getProduct(purchase).getCategoryId())
                .isEqualTo(expectedCategoryId);
    }

    /**
     * Verify rating
     */
    public void verifyRating(PurchaseResult purchase, BigDecimal expectedRating) {

        assertThat(getProduct(purchase).getRating())
                .isEqualByComparingTo(expectedRating);
    }

    /**
     * Verify review count
     */
    public void verifyReviewCount(PurchaseResult purchase, int expectedCount) {

        assertThat(getProduct(purchase).getReviewCount())
                .isEqualTo(expectedCount);
    }
}