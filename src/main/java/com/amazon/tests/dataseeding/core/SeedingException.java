// SeedingException.java
package com.amazon.tests.dataseeding.core;

public class SeedingException extends Exception {
    public SeedingException(String message) {
        super(message);
    }

    public SeedingException(String message, Throwable cause) {
        super(message, cause);
    }
}
