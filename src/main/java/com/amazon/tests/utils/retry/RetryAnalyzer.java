package com.amazon.tests.utils.retry;

import com.amazon.tests.utils.metrics.MetricsManager;
import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

public class RetryAnalyzer implements IRetryAnalyzer {
    int retryCount = 0;
    int maxRetries = 3;
    @Override
    public boolean retry(ITestResult iTestResult) {
        MetricsManager.getInstance()
                .recordRetry();

        return retryCount++ < maxRetries;
    }
}
