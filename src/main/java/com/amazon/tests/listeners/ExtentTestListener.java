package com.amazon.tests.listeners;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.reports.ExtentReportManager;
import com.amazon.tests.reports.ReportingFilter;
import com.aventstack.extentreports.markuputils.MarkupHelper;
import io.restassured.response.Response;
import lombok.extern.slf4j.Slf4j;
import org.testng.ISuite;
import org.testng.ISuiteListener;
import org.testng.ITestListener;
import org.testng.ITestResult;

@Slf4j
public class ExtentTestListener implements ITestListener, ISuiteListener {

    private final boolean extentEnabled = ConfigManager.getInstance().isReporterEnabled("extent");

    @Override
    public void onStart(ISuite suite) {
        log.info("[SUITE] Started: {}", suite.getName());
        if (!extentEnabled) return;
        ExtentReportManager.getInstance();
    }

    @Override
    public void onFinish(ISuite suite) {
        log.info("[SUITE] Finished: {}", suite.getName());
        if (!extentEnabled) return;
        ExtentReportManager.getInstance().flush();
    }

    @Override
    public void onTestStart(ITestResult result) {
        log.info("[TEST] Started");
        if (!extentEnabled) return;
        if (result.getMethod().getGroups().length > 0) {
            ExtentReportManager.getInstance().assignCategory(result.getMethod().getGroups());
        }
    }

    @Override
    public void onTestSuccess(ITestResult result) {
        log.info("[TEST] Passed");
        if (!extentEnabled) return;
        ExtentReportManager.getInstance().logPass("Test passed successfully");
        ExtentReportManager.getInstance().removeTest();
        ReportingFilter.clearLastResponse();
    }

    @Override
    public void onTestFailure(ITestResult result) {
        log.error("[TEST] Failed: {}", result.getThrowable().getMessage());
        if (!extentEnabled) return;

        Response last = ReportingFilter.getLastResponse();
        if (last != null) {
            String detail = last.getStatusLine() + "\n" + last.getBody().asPrettyString();
            log.debug("[HTTP] {}", detail);
            ExtentReportManager.getInstance().getTest().fail(MarkupHelper.createCodeBlock(detail));
        }

        ExtentReportManager.getInstance().getTest().fail(result.getThrowable());
        ExtentReportManager.getInstance().removeTest();
        ReportingFilter.clearLastResponse();
    }

    @Override
    public void onTestSkipped(ITestResult result) {
        log.warn("[TEST] Skipped");
        if (!extentEnabled) return;

        ExtentReportManager.getInstance().logSkip("Test skipped");
        if (result.getThrowable() != null) {
            ExtentReportManager.getInstance().logSkip("Reason: " + result.getThrowable().getMessage());
        }
        ExtentReportManager.getInstance().removeTest();
        ReportingFilter.clearLastResponse();
    }

    @Override
    public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
        // Not used in this framework
    }

    @Override
    public void onTestFailedWithTimeout(ITestResult result) {
        onTestFailure(result);
    }
}