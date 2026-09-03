package com.amazon.tests.validators.transport.request;

import com.amazon.tests.transport.ServiceRequest;

abstract class AbstractRequestValidationHandler implements RequestValidationHandler {
    private RequestValidationHandler next;
    public void setNext(RequestValidationHandler next) { this.next = next; }

    public final void validate(ServiceRequest request) {
        check(request);                          // this link's own check — can throw
        if (next != null) next.validate(request); // then pass along regardless
    }

    protected abstract void check(ServiceRequest request);
}

