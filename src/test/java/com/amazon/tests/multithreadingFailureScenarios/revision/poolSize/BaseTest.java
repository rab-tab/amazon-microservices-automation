package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;


import org.testng.ITestContext;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;

/**
 * Each module has its own BaseTest extending this.
 * Each declares its profile — which DBs it uses heavily.
 */
public abstract class BaseTest {

    // ── Each module overrides these two methods ──────────────────────

    protected abstract String getSuiteName();

    protected abstract MultiSuitePoolCalculator.ModuleProfile
    getModuleProfile();

    // ── Shared manager — same instance across all modules ────────────
    protected static final SharedPoolManager poolManager
            = SharedPoolManager.getInstance();

    @BeforeSuite
    public void setupSuite(ITestContext context) {
        int threadCount = context.getSuite()
                .getXmlSuite()
                .getThreadCount();

        poolManager.onSuiteStart(
                getSuiteName(),
                threadCount,
                getModuleProfile(),
                new TestEnvironmentConfig()
        );
    }

    @AfterSuite
    public void teardownSuite() {
        poolManager.onSuiteFinish(getSuiteName());
    }

    protected io.restassured.specification.RequestSpecification spec() {
        return poolManager.getSpec(
                new TestEnvironmentConfig().getBaseUrl());
    }
}
