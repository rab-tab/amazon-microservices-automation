package com.amazon.tests.provider;


import com.amazon.tests.factory.excel.ExcelReader;

public class ExcelDataProvider implements TestDataProvider {

    private final ExcelReader excelReader;

    public ExcelDataProvider(ExcelReader excelReader) {
        this.excelReader = excelReader;
    }

    @Override
    public <T> T load(Class<T> clazz, String key) {

        if (clazz == null) {
            throw new IllegalArgumentException("Class type cannot be null.");
        }

        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Key cannot be null or empty.");
        }

        return excelReader.read(clazz, key);
    }
}