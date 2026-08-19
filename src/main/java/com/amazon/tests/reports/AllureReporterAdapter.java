package com.amazon.tests.reports;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;

@Slf4j
public class AllureReporterAdapter implements TestReporter{

    @Override
    public void logStep(String message) {
        try {
            Allure.step(message);
        } catch (Exception e) {
            log.debug("Allure not available, skipping step log", e);
        }
    }

    @Override
    public void logPass(String message) {
        try {
            Allure.step(message, Status.PASSED);
        } catch (Exception e) {
            log.debug("Allure not available, skipping pass log", e);
        }
    }

    @Override
    public void logFail(String message, Throwable cause) {
        try {
            Allure.step(message, Status.FAILED);
            Allure.addAttachment("Stacktrace", String.valueOf(cause));
        } catch (Exception e) {
            log.debug("Allure not available, skipping fail log", e);
        }
    }

    @Override
    public void attachScreenshot(String path, String label) {
        try {
            Allure.addAttachment(label, new FileInputStream(path));
        } catch (Exception e) {
            log.debug("Allure not available, skipping screenshot", e);
        }
    }
}
