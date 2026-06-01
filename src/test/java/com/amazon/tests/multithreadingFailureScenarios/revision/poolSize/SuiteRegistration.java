package com.amazon.tests.multithreadingFailureScenarios.revision.poolSize;

// New standalone file: SuiteRegistration.java




public record SuiteRegistration(
        String suiteName,
        int threadCount,
        MultiSuitePoolCalculator.ModuleProfile profile
) {

}