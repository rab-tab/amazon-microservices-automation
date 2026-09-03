package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.HttpMethod;
import com.amazon.tests.transport.ServiceRequest;

public class PostRequiresPayloadValidator extends AbstractRequestValidationHandler {
    protected void check(ServiceRequest request) {
        if (request.getMethod() == HttpMethod.POST && request.getPayload() == null) {
            throw new IllegalStateException("POST request to " + request.getEndpoint() + " has no payload");
        }
    }
}
