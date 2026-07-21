package com.amazon.tests.auth;

public class NoAuthStrategy implements AuthStrategy {


    @Override
    public String getToken() {
        return null;
    }
}
