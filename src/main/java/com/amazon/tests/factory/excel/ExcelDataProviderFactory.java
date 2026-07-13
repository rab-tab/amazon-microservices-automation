package com.amazon.tests.factory.excel;

import com.amazon.tests.builder.ExcelDataProviderBuilder;
import com.amazon.tests.factory.TestDataProviderFactory;
import com.amazon.tests.provider.TestDataProvider;

public class ExcelDataProviderFactory implements TestDataProviderFactory {

    @Override
    public TestDataProvider createProvider() {

        return ExcelDataProviderBuilder.builder()
                .withWorkbook(
                        WorkbookLoader.load("src/test/resources/testdata/users.xlsx"))
                .build();
    }
}