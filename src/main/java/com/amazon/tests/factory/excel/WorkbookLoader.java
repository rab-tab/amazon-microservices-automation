package com.amazon.tests.factory.excel;


import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public final class WorkbookLoader {

    private WorkbookLoader() {
    }

    public static Workbook load(String filePath) {

        try (FileInputStream fis = new FileInputStream(new File(filePath))) {

            return WorkbookFactory.create(fis);

        } catch (IOException e) {

            throw new RuntimeException(
                    "Unable to load workbook : " + filePath,
                    e);
        }
    }
}
