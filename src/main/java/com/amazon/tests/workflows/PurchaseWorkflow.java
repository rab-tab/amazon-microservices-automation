package com.amazon.tests.workflows;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.facade.AuthFacade;
import com.amazon.tests.utils.facade.OrderFacade;
import com.amazon.tests.utils.facade.PaymentFacade;
import com.amazon.tests.utils.facade.ProductFacade;

public class PurchaseWorkflow {

    private final AuthFacade authFacade = new AuthFacade();

    private final ProductFacade productFacade = new ProductFacade();

    private final OrderFacade orderFacade = new OrderFacade();

    private final PaymentFacade paymentFacade = new PaymentFacade();

    private final PurchaseResult result = new PurchaseResult();

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

    public PurchaseWorkflow createProduct() {

        TestModels.ProductResponse product =
                productFacade.createProduct(result.getSellerAuth());

        result.setProduct(product);

        return this;
    }

    public PurchaseWorkflow browseProducts() {

        productFacade.browseProducts();

        return this;
    }

    public PurchaseWorkflow viewProduct() {

        productFacade.getProduct(result.getProduct().getId());

        return this;
    }

    public PurchaseWorkflow createOrder() {

        TestModels.OrderResponse order =
                orderFacade.createOrder(
                        result.getProduct().getId(),
                        result.getProduct().getName(),
                        result.getProduct().getPrice(),
                        result.getCustomerAuth().getUser().getId(),
                        result.getCustomerAuth().getAccessToken());

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

}
