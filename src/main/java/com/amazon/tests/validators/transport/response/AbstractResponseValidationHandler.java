package com.amazon.tests.validators.transport.response;

import com.amazon.tests.transport.ServiceResponse;

public abstract class AbstractResponseValidationHandler implements ResponseValidationHandler {

    private ResponseValidationHandler next;

    @Override
    public void setNext(ResponseValidationHandler next) {
        this.next = next;
    }

    @Override
    public final void validate(ServiceResponse response) {
        check(response);
        if (next != null) {
            next.validate(response);
        }
    }

    protected abstract void check(ServiceResponse response);
}


