package com.amazon.tests.workflows;

import com.amazon.tests.commonmodels.enums.ProductType;
import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.AuthFacade;
import com.amazon.tests.utils.facade.OrderFacade;
import com.amazon.tests.utils.facade.PaymentFacade;
import com.amazon.tests.utils.facade.ProductFacade;

import java.util.ArrayList;
import java.util.List;

public class PurchaseWorkflow {

    private final AuthFacade authFacade = new AuthFacade();

    private final ProductFacade productFacade = new ProductFacade();

    private final OrderFacade orderFacade = new OrderFacade();

    private final PaymentFacade paymentFacade = new PaymentFacade();

    private final PurchaseResult result = new PurchaseResult();
    private List<TestModels.ProductResponse> products = new ArrayList<>();

    public static PurchaseWorkflow start() {
        return new PurchaseWorkflow();
    }

    public PurchaseWorkflow registerCustomer() {

        result.setCustomer(authFacade.registerCustomer());

        return this;
    }

    public PurchaseWorkflow loginCustomer() {

        TestModels.AuthResponse auth =
                authFacade.login(
                        result.getCustomer().getEmail(),
                        result.getCustomer().getPassword());

        result.setCustomerAuth(auth);

        return this;
    }

    public PurchaseWorkflow registerSeller() {

        result.setSellerAuth(authFacade.registerSeller());

        return this;
    }


    public PurchaseWorkflow createProduct(int count) {

        result.getProducts().addAll(
                productFacade.createProducts(result.getSellerAuth(), count));

        return this;
    }

    public PurchaseWorkflow createProducts(int count, ProductType type) {

        List<TestModels.ProductResponse> products =
                productFacade.createProducts(
                        result.getSellerAuth(),
                        count,
                        type);

        result.getProducts().addAll(products);

        return this;
    }
    public PurchaseWorkflow browseProducts() {

        productFacade.browseProducts();

        return this;
    }


    public PurchaseWorkflow viewProduct() {
        productFacade.getProduct(result.getFirstProduct().getId());
        return this;
    }
    public PurchaseWorkflow createOrder() {

        TestModels.OrderResponse order =
                orderFacade.createOrder(result);

        result.setOrder(order);

        return this;
    }

    public PurchaseWorkflow cancelOrder() {

        orderFacade.cancelOrder(
                result.getOrder().getId(),
                result.getCustomerAuth().getAccessToken(),
                result.getCustomerAuth().getUser().getId());

        return this;
    }

    public PurchaseResult execute() {

        return result;
    }
    /*public PurchaseWorkflow createMultiItemOrder(int minItems, int maxItems) {

        TestModels.OrderResponse order =
                orderFacade.createMultiItemOrder(
                        result.getProduct().getId(),
                        result.getCustomerAuth().getUser().getId(),
                        result.getCustomerAuth().getAccessToken(),
                        minItems,
                        maxItems);

        result.setOrder(order);

        return this;
    }*/

}
