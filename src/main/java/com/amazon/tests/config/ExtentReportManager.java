package com.amazon.tests.config;


import com.aventstack.extentreports.ExtentReports;
import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.reporter.ExtentSparkReporter;
import com.aventstack.extentreports.reporter.configuration.Theme;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Thread-safe ExtentReports manager using Singleton + ThreadLocal pattern.
 *
 * SINGLETON: One ExtentReports instance (one HTML report)
 * THREADLOCAL: Per-thread ExtentTest nodes (for parallel execution)
 */
@Slf4j
public class ExtentReportManager {

    // ===== SINGLETON PART: One ExtentReports instance =====
    private static volatile ExtentReportManager instance;
    private final ExtentReports extent;

    // ===== THREADLOCAL PART: Per-thread ExtentTest nodes =====
    private final ThreadLocal<ExtentTest> test = new ThreadLocal<>();

    /**
     * Private constructor (Singleton pattern)
     * Initializes the single ExtentReports instance
     */
    private ExtentReportManager() {
        log.info("Initializing ExtentReportManager...");

        // Create reports directory
        String reportDir = "target/extent-reports/";
        new File(reportDir).mkdirs();

        // Generate timestamped report filename
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
        String reportPath = reportDir + "TestReport_" + timestamp + ".html";

        // Configure Spark Reporter
        ExtentSparkReporter sparkReporter = new ExtentSparkReporter(reportPath);
        sparkReporter.config().setDocumentTitle("Amazon API Test Report");
        sparkReporter.config().setReportName("API Automation Results");
        sparkReporter.config().setTheme(Theme.DARK);
        sparkReporter.config().setEncoding("UTF-8");
        sparkReporter.config().setTimeStampFormat("MMM dd, yyyy HH:mm:ss");

        // Create ExtentReports instance (SINGLETON)
        extent = new ExtentReports();
        extent.attachReporter(sparkReporter);

        // System information
        extent.setSystemInfo("Application", "Amazon Microservices");
        extent.setSystemInfo("Environment", System.getProperty("env", "local"));
        extent.setSystemInfo("User", System.getProperty("user.name"));
        extent.setSystemInfo("OS", System.getProperty("os.name"));
        extent.setSystemInfo("Java Version", System.getProperty("java.version"));

        log.info("ExtentReportManager initialized. Report: {}", reportPath);
    }

    /**
     * Get singleton instance (thread-safe with double-check locking)
     */
    public static ExtentReportManager getInstance() {
        if (instance == null) {
            synchronized (ExtentReportManager.class) {
                if (instance == null) {
                    instance = new ExtentReportManager();
                }
            }
        }
        return instance;
    }

    /**
     * Get the singleton ExtentReports instance
     */
    public ExtentReports getExtentReports() {
        return extent;
    }

    /**
     * Create a test node for the current thread
     * @param testName Name of the test
     * @param description Test description
     */
    public void createTest(String testName, String description) {
        ExtentTest extentTest = extent.createTest(testName, description);
        test.set(extentTest);
        log.debug("Thread {}: Created test node '{}'",
                Thread.currentThread().getId(), testName);
    }

    /**
     * Create a test node for the current thread (without description)
     * @param testName Name of the test
     */
    public void createTest(String testName) {
        createTest(testName, "");
    }

    /**
     * Get the ExtentTest node for the current thread
     */
    public ExtentTest getTest() {
        ExtentTest currentTest = test.get();
        if (currentTest == null) {
            log.warn("Thread {}: No test node found. Creating default node.",
                    Thread.currentThread().getId());
            createTest("Unnamed Test");
            return test.get();
        }
        return currentTest;
    }

    /**
     * Log info message to current thread's test
     */
    public void logInfo(String message) {
        getTest().info(message);
    }

    /**
     * Log pass message to current thread's test
     */
    public void logPass(String message) {
        getTest().pass(message);
    }

    /**
     * Log fail message to current thread's test
     */
    public void logFail(String message) {
        getTest().fail(message);
    }

    /**
     * Log skip message to current thread's test
     */
    public void logSkip(String message) {
        getTest().skip(message);
    }

    /**
     * Log warning message to current thread's test
     */
    public void logWarning(String message) {
        getTest().warning(message);
    }

    /**
     * Assign category to current thread's test
     */
    public void assignCategory(String... categories) {
        getTest().assignCategory(categories);
    }

    /**
     * Assign author to current thread's test
     */
    public void assignAuthor(String... authors) {
        getTest().assignAuthor(authors);
    }

    /**
     * Add screenshot to current thread's test
     * @param screenshotPath Path to screenshot file
     */
    public void addScreenshot(String screenshotPath) {
        try {
            getTest().addScreenCaptureFromPath(screenshotPath);
        } catch (Exception e) {
            log.error("Failed to attach screenshot: {}", e.getMessage());
        }
    }

    /**
     * Remove test node from ThreadLocal (call in @AfterMethod)
     * CRITICAL: Prevents memory leaks in thread pools
     */
    public void removeTest() {
        test.remove();
        log.debug("Thread {}: Removed test node from ThreadLocal",
                Thread.currentThread().getId());
    }

    /**
     * Flush the report (write to disk)
     * Call in @AfterSuite
     */
    public synchronized void flush() {
        extent.flush();
        log.info("ExtentReports flushed to disk");
    }
}
