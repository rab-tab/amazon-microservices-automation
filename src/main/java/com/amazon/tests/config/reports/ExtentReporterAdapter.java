package com.amazon.tests.config.reports;

public class ExtentReporterAdapter implements TestReporter {

    @Override
    public void logStep(String message) {
        ExtentReportManager.getInstance().logInfo(message);
    }

    @Override
    public void logPass(String message) {
        ExtentReportManager.getInstance().logPass(message);
    }

    @Override
    public void logFail(String message, Throwable cause) {
        ExtentReportManager.getInstance().logFail(message + "\n" + cause);
    }

    @Override
    public void attachScreenshot(String path, String label) {
        ExtentReportManager.getInstance().addScreenshot(path);
    }
}

