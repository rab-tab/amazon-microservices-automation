package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.ServiceRequest;

public interface RequestValidationHandler {
    void setNext(RequestValidationHandler next);
    void validate(ServiceRequest request);
}
