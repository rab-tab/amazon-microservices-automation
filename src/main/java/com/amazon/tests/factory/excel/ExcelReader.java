package com.amazon.tests.factory.excel;

import org.apache.poi.ss.usermodel.Workbook;

public class ExcelReader {

    private final Workbook workbook;
    private final WorkbookCache cache;
    private final ExcelParser parser;

    public ExcelReader(Workbook workbook,
                       WorkbookCache cache,
                       ExcelParser parser) {

        this.workbook = workbook;
        this.cache = cache;
        this.parser = parser;
    }

    public <T> T read(Class<T> clazz,
                      String key) {

        // Find sheet
        // Find row
        // parser.parse(...)
        return null;
    }
}