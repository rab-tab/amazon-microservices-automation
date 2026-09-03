package com.amazon.tests.validators.transport.response;

import com.amazon.tests.transport.ServiceResponse;

public class ResponseValidationChain {
    private static final ResponseValidationHandler CHAIN = buildChain();

    private static ResponseValidationHandler buildChain() {
        ResponseValidationHandler statusCodeCheck = new StatusCodeSaneValidator();
        ResponseValidationHandler bodyCheck = new ResponseBodyNotNullValidator();

        statusCodeCheck.setNext(bodyCheck);

        return statusCodeCheck;
    }

    public static void validate(ServiceResponse response) {
        CHAIN.validate(response);
    }

    private ResponseValidationChain() {
    }
}
