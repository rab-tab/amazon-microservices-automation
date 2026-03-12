package com.amazon.tests.tests;

import com.amazon.tests.config.RestAssuredConfig;
import com.amazon.tests.listeners.AllureTestListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.restassured.RestAssured;
import lombok.extern.slf4j.Slf4j;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Listeners;

@Slf4j
@Listeners(AllureTestListener.class)
public abstract class BaseTest {

    protected static final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @BeforeSuite(alwaysRun = true)
    public void setupSuite() {
        log.info("Initializing REST Assured test suite...");
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        log.info("Base URL: {}", RestAssuredConfig.getConfig().baseUrl());
        log.info("Test suite setup complete.");
    }

    protected void logStep(String step) {
        log.info("→ STEP: {}", step);
    }
}
