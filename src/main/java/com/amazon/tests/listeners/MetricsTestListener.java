package com.amazon.tests.listeners;

import com.amazon.tests.utils.metrics.MetricsManager;
import org.testng.ITestListener;
import org.testng.ITestResult;

public class MetricsTestListener
        implements ITestListener {

    @Override
    public void onTestSuccess(
            ITestResult result) {

        MetricsManager.getInstance()
                .recordTestPassed();
    }

    @Override
    public void onTestFailure(
            ITestResult result) {

        MetricsManager.getInstance()
                .recordTestFailed();
    }
}
