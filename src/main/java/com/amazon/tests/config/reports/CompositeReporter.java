package com.amazon.tests.config.reports;

import java.util.List;

public class CompositeReporter implements TestReporter {
    private final List<TestReporter> reporters;

    public CompositeReporter(List<TestReporter> reporters) {
        this.reporters = reporters;
    }

    @Override
    public void logStep(String message) {
        reporters.forEach(r -> r.logStep(message));
    }

    @Override
    public void logPass(String message) {
        reporters.forEach(r -> r.logPass(message));
    }

    @Override
    public void logFail(String message, Throwable cause) {
        reporters.forEach(r -> r.logFail(message, cause));
    }

    @Override
    public void attachScreenshot(String path, String label) {
        reporters.forEach(r -> r.attachScreenshot(path, label));
    }
}
