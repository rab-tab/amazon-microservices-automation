package com.amazon.tests.validators.transport.response;

public class InvalidServiceResponseException extends RuntimeException {
    public InvalidServiceResponseException(String message) {
        super(message);
    }
}
