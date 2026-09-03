package com.amazon.tests.validators.transport.response;

import com.amazon.tests.transport.ServiceResponse;

public interface ResponseValidationHandler {
    void setNext(ResponseValidationHandler next);
    void validate(ServiceResponse response);
}
