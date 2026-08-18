package com.amazon.tests.datasource;

public class TestDataLoadException extends RuntimeException {
    public TestDataLoadException(String message, Throwable cause) {
        super(message, cause);
    }
    public TestDataLoadException(String message) {
        super(message);
    }
}