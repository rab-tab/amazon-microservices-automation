package com.amazon.tests.listeners;

import io.qameta.allure.Attachment;
import lombok.extern.slf4j.Slf4j;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
public class AllureTestListener implements ITestListener {

    @Override
    public void onTestStart(ITestResult result) {
        log.info("▶ Starting test: {}.{}", result.getTestClass().getName(), result.getName());
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        log.info("✅ PASSED: {} ({}ms)", result.getName(),
                result.getEndMillis() - result.getStartMillis());
    }

    @Override
    public void onTestFailure(ITestResult result) {
        log.error("❌ FAILED: {} - {}", result.getName(),
                result.getThrowable() != null ? result.getThrowable().getMessage() : "Unknown error");
        if (result.getThrowable() != null) {
            attachFailureDetails(result.getThrowable().getMessage());
        }
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        log.warn("⏭ SKIPPED: {}", result.getName());
    }

    @Override
    public void onStart(ITestContext context) {
        log.info("🚀 Test Suite started: {} at {}", context.getName(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
    }

    @Override
    public void onFinish(ITestContext context) {
        log.info("🏁 Test Suite finished: {} | Passed: {} | Failed: {} | Skipped: {}",
                context.getName(),
                context.getPassedTests().size(),
                context.getFailedTests().size(),
                context.getSkippedTests().size());
    }

    @Attachment(value = "Failure Details", type = "text/plain")
    private String attachFailureDetails(String message) {
        return message != null ? message : "No failure message available";
    }
}
