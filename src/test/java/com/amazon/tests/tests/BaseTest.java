package com.amazon.tests.tests;

import com.amazon.tests.config.ConfigManager;
import com.amazon.tests.config.ExtentReportManager;
import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.listeners.AllureTestListener;
import com.amazon.tests.utils.DatabaseValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

@Slf4j
@Listeners(AllureTestListener.class)
public abstract class BaseTest {

    protected static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeSuite(alwaysRun = true)
    public void setupSuite() {
        log.info("Initializing test suite...");

        // Initialize RestAssured
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();

        // Initialize DatabaseValidator (creates connection pools)
        DatabaseValidator.getInstance();

        // Initialize ExtentReportManager (creates report)
        ExtentReportManager.getInstance();

        // Log configuration
        //log.info("Environment: {}", ConfigManager.getInstance().getEnvironment());
        log.info("Base URL: {}", ConfigManager.getInstance().getBaseUrl());
        log.info("User Service URL: {}", ConfigManager.getInstance().getUserServiceUrl());
        log.info("Product Service URL: {}", ConfigManager.getInstance().getProductServiceUrl());
        log.info("Order Service URL: {}", ConfigManager.getInstance().getOrderServiceUrl());

        log.info("Test suite setup complete.");
    }

    // ✅ Clean up ThreadLocal to prevent memory leaks
    @AfterMethod(alwaysRun = true)
    public void cleanupThreadLocal() {
        RestAssuredConfig.clearCache();
    }

    @AfterSuite(alwaysRun = true)
    public void tearDownSuite() {
        log.info("Shutting down test suite...");

        // Shutdown database connection pools
        DatabaseValidator.getInstance().shutdown();

        // Flush Extent Reports (already done in listener, but safe to call again)
        ExtentReportManager.getInstance().flush();

        log.info("Test suite shutdown complete.");
    }

    protected void logStep(String step) {
        log.info("→ STEP: {}", step);
    }
}
