package com.amazon.tests.validators.transport.request;

public class InvalidServiceRequestException extends RuntimeException {
    public InvalidServiceRequestException(String message) {
        super(message);
    }
}
