package com.amazon.tests.reports;


    public interface TestReporter {
        void logStep(String message);
        void logPass(String message);
        void logFail(String message, Throwable cause);
        void attachScreenshot(String path, String label);
    }

