package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.ServiceRequest;

public class TargetServiceRequiredValidator extends AbstractRequestValidationHandler {

    @Override
    protected void check(ServiceRequest request) {
        if (request.getTargetService() == null) {
            throw new InvalidServiceRequestException(
                    "ServiceRequest for endpoint '" + request.getEndpoint()
                            + "' has no targetService set — spec resolution will fail. "
                            + "Did you forget .targetService(ServiceType.X) when building this request?");
        }
    }
}
