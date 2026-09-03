package com.amazon.tests.validators.transport.response;

import com.amazon.tests.transport.ServiceResponse;

public class ResponseBodyNotNullValidator extends AbstractResponseValidationHandler {

    @Override
    protected void check(ServiceResponse response) {
        if (response.getBody() == null) {
            throw new InvalidServiceResponseException(
                    "Response body is null for a " + response.getStatusCode()
                            + " response — expected at minimum an empty string. "
                            + "This usually indicates a malformed connection close, not a "
                            + "real, complete API response.");
        }
    }
}
