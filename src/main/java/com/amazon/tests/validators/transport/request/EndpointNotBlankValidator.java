package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.ServiceRequest;

public class EndpointNotBlankValidator extends AbstractRequestValidationHandler {

    @Override
    protected void check(ServiceRequest request) {
        if (request.getEndpoint() == null || request.getEndpoint().isBlank()) {
            throw new InvalidServiceRequestException(
                    "ServiceRequest has a null/blank endpoint for method " + request.getMethod());
        }
        if (request.getMethod() == null) {
            throw new InvalidServiceRequestException(
                    "ServiceRequest for endpoint '" + request.getEndpoint() + "' has no HttpMethod set");
        }
    }
}

