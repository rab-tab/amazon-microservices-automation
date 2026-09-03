package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.ServiceRequest;

public class RequestValidationChain {
    private static final RequestValidationHandler CHAIN = buildChain();

    private static RequestValidationHandler buildChain() {
        RequestValidationHandler endpointCheck = new EndpointNotBlankValidator();
        RequestValidationHandler targetServiceCheck = new TargetServiceRequiredValidator();
        RequestValidationHandler pathParamsCheck = new PathParamsPresentValidator();
        RequestValidationHandler payloadCheck = new PayloadRequiredForWriteValidator();

        endpointCheck.setNext(targetServiceCheck);
        targetServiceCheck.setNext(pathParamsCheck);
        pathParamsCheck.setNext(payloadCheck);
        // payloadCheck.setNext(null) — end of chain, implicit

        return endpointCheck;
    }

    public static void validate(ServiceRequest request) {
        CHAIN.validate(request);
    }
    private RequestValidationChain() {
        // static utility — not instantiable
    }
}
