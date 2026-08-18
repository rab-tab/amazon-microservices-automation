package com.amazon.tests.config.reports;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Slf4jReporterAdapter implements TestReporter {

    @Override
    public void logStep(String message) {
        log.info("STEP: {}", message);
    }

    @Override
    public void logPass(String message) {
        log.info("PASS: {}", message);
    }

    @Override
    public void logFail(String message, Throwable cause) {
        log.error("FAIL: {}", message, cause);
    }

    @Override
    public void attachScreenshot(String path, String label) {
        log.info("SCREENSHOT [{}]: {}", label, path);
    }
}
