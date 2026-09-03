package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.HttpMethod;
import com.amazon.tests.transport.ServiceRequest;

public class PayloadRequiredForWriteValidator extends AbstractRequestValidationHandler {

    @Override
    protected void check(ServiceRequest request) {
        boolean isWriteMethod = request.getMethod() == HttpMethod.POST
                || request.getMethod() == HttpMethod.PUT
                || request.getMethod() == HttpMethod.PATCH;

        if (isWriteMethod && request.getPayload() == null) {
            throw new InvalidServiceRequestException(
                    request.getMethod() + " request to '" + request.getEndpoint()
                            + "' has no payload set. If this is intentional (e.g. a deliberate "
                            + "negative-path test), use createOrderWithFault(...)-style methods "
                            + "that explicitly build an empty/invalid payload instead of leaving "
                            + "it null by omission.");
        }
    }
}
