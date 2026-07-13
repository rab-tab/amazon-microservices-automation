package com.amazon.tests.builder;

import com.amazon.tests.factory.excel.ExcelParser;
import com.amazon.tests.factory.excel.ExcelReader;
import com.amazon.tests.factory.excel.WorkbookCache;
import com.amazon.tests.provider.ExcelDataProvider;
import org.apache.poi.ss.usermodel.Workbook;

public class ExcelDataProviderBuilder {

    private Workbook workbook;
    private WorkbookCache workbookCache;
    private ExcelParser excelParser;
    private ExcelReader excelReader;

    private ExcelDataProviderBuilder() {
    }

    public static ExcelDataProviderBuilder builder() {
        return new ExcelDataProviderBuilder();
    }

    public ExcelDataProviderBuilder withWorkbook(Workbook workbook) {
        this.workbook = workbook;
        return this;
    }

    public ExcelDataProviderBuilder withWorkbookCache(WorkbookCache workbookCache) {
        this.workbookCache = workbookCache;
        return this;
    }

    public ExcelDataProviderBuilder withExcelParser(ExcelParser excelParser) {
        this.excelParser = excelParser;
        return this;
    }

    public ExcelDataProviderBuilder withExcelReader(ExcelReader excelReader) {
        this.excelReader = excelReader;
        return this;
    }

    public ExcelDataProvider build() {

        validate();

        if (workbookCache == null) {
            workbookCache = new WorkbookCache();
        }

        if (excelParser == null) {
            excelParser = new ExcelParser();
        }

        if (excelReader == null) {
            excelReader = new ExcelReader(
                    workbook,
                    workbookCache,
                    excelParser);
        }

        return new ExcelDataProvider(excelReader);
    }

    private void validate() {

        if (workbook == null) {
            throw new IllegalStateException(
                    "Workbook is mandatory.");
        }
    }
}