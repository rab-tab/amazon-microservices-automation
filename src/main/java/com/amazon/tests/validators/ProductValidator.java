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
                purchase.getProducts().get(0).getId());
    }

    /**
     * Verify product is ACTIVE
     */
    private void verifyProductIsActive(TestModels.ProductResponse product) {

        assertThat(product.getStatus().equals("ACTIVE"));

    }
    public void verifyProductsRemainActive(PurchaseResult purchase) {

        purchase.getProducts().forEach(product -> {
            verifyProductIsActive(product);
            //verifyProductBelongsToSeller(product, purchase.getSellerAuth());
        });
    }
    private void verifyProductIdGenerated(TestModels.ProductResponse product) {

        assertThat(product.getId()).isNotBlank();
    }



    public void verifyProductsCreated(PurchaseResult purchase) {

        purchase.getProducts()
                .forEach(this::verifyProductCreated);
    }
    public void verifyProductCreated(PurchaseResult purchase) {

        purchase.getProducts().forEach(this::verifyProductCreated);
    }

    private void verifyProductCreated(TestModels.ProductResponse product) {

        verifyProductName(product);
        verifyProductDescription(product);
        verifyProductPrice(product);
        verifyProductIsActive(product);
    }
    /**
     * Verify product name
     */
    public void verifyProductName(TestModels.ProductResponse product) {

        assertThat(product.getName()).isNotBlank();
    }

    /**
     * Verify product description
     */
    public void verifyProductDescription(TestModels.ProductResponse product) {

        assertThat(product.getDescription()).isNotBlank();
    }

    /**
     * Verify product price
     */
    private void verifyProductPrice(TestModels.ProductResponse product) {

        assertThat(product.getPrice())
                .isNotNull()
                .isPositive();
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