package com.amazon.tests.testData;

import com.amazon.tests.models.TestModels;

public class ProductData {
    private int count = 1;

    private PriceType priceType = PriceType.MEDIUM;

    private StockType stockType = StockType.MEDIUM;

    public ProductData count(int count) {

        this.count = count;
        return this;
    }

    public ProductData mediumPrice() {

        this.priceType = PriceType.MEDIUM;
        return this;
    }

    public ProductData lowPrice() {

        this.priceType = PriceType.LOW;
        return this;
    }

    public ProductData highPrice() {

        this.priceType = PriceType.HIGH;
        return this;
    }

    public ProductData highStock() {

        this.stockType = StockType.HIGH;
        return this;
    }

    public ProductData mediumStock() {

        this.stockType = StockType.MEDIUM;
        return this;
    }

    public ProductData lowStock() {

        this.stockType = StockType.LOW;
        return this;
    }

    public TestModels.ProductResponse create() {

        return null;
    }

    public int getCount() {
        return count;
    }

    public PriceType getPriceType() {
        return priceType;
    }

    public StockType getStockType() {
        return stockType;
    }
}
