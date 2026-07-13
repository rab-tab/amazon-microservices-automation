package com.amazon.tests.factory;

import com.amazon.tests.provider.TestDataProvider;

public interface TestDataProviderFactory {
    TestDataProvider createProvider();
}
