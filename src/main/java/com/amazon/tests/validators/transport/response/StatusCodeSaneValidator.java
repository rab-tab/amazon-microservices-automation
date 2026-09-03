package com.amazon.tests.validators.transport.response;

import com.amazon.tests.transport.ServiceResponse;

public class StatusCodeSaneValidator extends AbstractResponseValidationHandler {

    @Override
    protected void check(ServiceResponse response) {
        if (response.getStatusCode() <= 0) {
            throw new InvalidServiceResponseException(
                    "Received a non-HTTP status code (" + response.getStatusCode()
                            + ") — the request likely never reached the server "
                            + "(connection failure, DNS resolution failure, or timeout "
                            + "before any response was received).");
        }
    }
}
