package com.amazon.tests.listeners;


import com.amazon.tests.config.extentReports.ExtentReportManager;
import lombok.extern.slf4j.Slf4j;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

/**
 * TestNG listener that integrates with ExtentReportManager.
 * Automatically logs test results to Extent Reports.
 */
@Slf4j
public class ExtentTestListener implements ITestListener, ISuiteListener {

    @Override
    public void onStart(ISuite suite) {
        log.info("Test Suite Started: {}", suite.getName());
        // Initialize ExtentReportManager
        ExtentReportManager.getInstance();
    }

    @Override
    public void onFinish(ISuite suite) {
        log.info("Test Suite Finished: {}", suite.getName());
        // Flush the report
        ExtentReportManager.getInstance().flush();
    }

    @Override
    public void onTestStart(ITestResult result) {
        String testName = result.getMethod().getMethodName();
        String className = result.getTestClass().getName();
        String description = result.getMethod().getDescription();

        log.info("Test Started: {}.{}", className, testName);

        // Create test node for current thread
        ExtentReportManager.getInstance().createTest(
                className + "." + testName,
                description != null ? description : ""
        );

        // Add categories and author
        if (result.getMethod().getGroups().length > 0) {
            ExtentReportManager.getInstance().assignCategory(result.getMethod().getGroups());
        }

        ExtentReportManager.getInstance().logInfo("Test execution started");
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        log.info("Test Passed: {}", result.getMethod().getMethodName());

        ExtentReportManager.getInstance().logPass(
                "Test passed successfully: " + result.getMethod().getMethodName()
        );

        // Remove from ThreadLocal
        ExtentReportManager.getInstance().removeTest();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        log.error("Test Failed: {}", result.getMethod().getMethodName());

        // Log failure
        ExtentReportManager.getInstance().logFail(
                "Test failed: " + result.getThrowable().getMessage()
        );

        // Capture screenshot
       /* String screenshotPath = ScreenshotUtil.captureScreenshot(
                result.getMethod().getMethodName()
        );

        if (screenshotPath != null) {
            ExtentReportManager.getInstance().addScreenshot(screenshotPath);
        }*/

        // Log stack trace
        ExtentReportManager.getInstance().logFail(
                "<pre>" + getStackTrace(result.getThrowable()) + "</pre>"
        );

        // Remove from ThreadLocal
        ExtentReportManager.getInstance().removeTest();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        log.warn("Test Skipped: {}", result.getMethod().getMethodName());

        ExtentReportManager.getInstance().logSkip(
                "Test skipped: " + result.getMethod().getMethodName()
        );

        if (result.getThrowable() != null) {
            ExtentReportManager.getInstance().logSkip(
                    "Reason: " + result.getThrowable().getMessage()
            );
        }

        // Remove from ThreadLocal
        ExtentReportManager.getInstance().removeTest();
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        // Not used in this framework
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        onTestFailure(result);
    }

    private String getStackTrace(Throwable throwable) {
        StringBuilder sb = new StringBuilder();
        sb.append(throwable.toString()).append("\n");
        for (StackTraceElement element : throwable.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}