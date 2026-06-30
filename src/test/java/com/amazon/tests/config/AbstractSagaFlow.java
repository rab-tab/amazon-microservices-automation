package com.amazon.tests.config;


import com.amazon.tests.dataseeding.core.SeedingContext;

public abstract class AbstractSagaFlow {
    protected final SeedingContext context;
    protected final TestEnvironment env;

    protected AbstractSagaFlow(SeedingContext context,
                               TestEnvironment env) {
        this.context = context;
        this.env = env;
    }
}
