package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.HttpMethod;
import com.amazon.tests.transport.RequestAttributes;
import com.amazon.tests.transport.ServiceRequest;

import java.util.Map;

public class PayloadRequiredForWriteValidator extends AbstractRequestValidationHandler {

    @Override
    protected void check(ServiceRequest request) {
        boolean isWriteMethod = request.getMethod() == HttpMethod.POST
                || request.getMethod() == HttpMethod.PUT
                || request.getMethod() == HttpMethod.PATCH;

        if (!isWriteMethod) {
            return;
        }

        if (request.getPayload() != null) {
            return; // has a body — fine
        }

        Map<?, ?> queryParams = request.getAttribute(RequestAttributes.QUERY_PARAMS, Map.class);
        if (queryParams != null && !queryParams.isEmpty()) {
            return; // data is present via query params — fine
        }

        throw new InvalidServiceRequestException(
                request.getMethod() + " request to '" + request.getEndpoint()
                        + "' has no payload AND no query params. If this is intentional (e.g. a "
                        + "deliberate negative-path test), use createOrderWithFault(...)-style "
                        + "methods that explicitly build an empty/invalid payload instead of "
                        + "leaving it null by omission.");
    }
}