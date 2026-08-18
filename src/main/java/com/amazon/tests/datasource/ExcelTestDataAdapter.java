package com.amazon.tests.datasource;


import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExcelTestDataAdapter<T> implements TestDataSource<T> {

    @Override
    public List<T> loadData(String sourceLocation, Class<T> type) {
        File file = new File(sourceLocation);
        if (!file.exists()) {
            throw new TestDataLoadException("Excel test data file not found: " + sourceLocation);
        }

        List<T> results = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new TestDataLoadException("Excel sheet has no header row: " + sourceLocation);
            }

            Map<Integer, String> columnIndexToField = new HashMap<>();
            for (Cell cell : headerRow) {
                columnIndexToField.put(cell.getColumnIndex(), cell.getStringCellValue().trim());
            }

            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                if (row.getRowNum() == 0) continue; // skip header
                if (isRowBlank(row)) continue;
                results.add(mapRowToObject(row, type, columnIndexToField, formatter));
            }
        } catch (IOException e) {
            throw new TestDataLoadException("Failed to parse Excel test data: " + sourceLocation, e);
        }
        return results;
    }

    private boolean isRowBlank(Row row) {
        for (Cell cell : row) {
            if (cell.getCellType() != CellType.BLANK) return false;
        }
        return true;
    }

    private T mapRowToObject(Row row, Class<T> type,
                             Map<Integer, String> columnIndexToField,
                             DataFormatter formatter) {
        try {
            T instance = type.getDeclaredConstructor().newInstance();

            for (Map.Entry<Integer, String> entry : columnIndexToField.entrySet()) {
                Cell cell = row.getCell(entry.getKey());
                if (cell == null) continue;

                Field field;
                try {
                    field = type.getDeclaredField(entry.getValue());
                } catch (NoSuchFieldException e) {
                    // Column in sheet with no matching model field — skip rather than fail the whole load
                    continue;
                }
                field.setAccessible(true);
                setFieldValue(instance, field, cell, formatter);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new TestDataLoadException(
                    "Failed to map Excel row " + row.getRowNum() + " to " + type.getSimpleName(), e);
        }
    }

    private void setFieldValue(Object instance, Field field, Cell cell, DataFormatter formatter)
            throws IllegalAccessException {
        Class<?> fieldType = field.getType();
        String raw = formatter.formatCellValue(cell).trim();

        if (raw.isEmpty()) return; // leave default (null/0/false)

        if (fieldType == String.class) {
            field.set(instance, raw);
        } else if (fieldType == int.class || fieldType == Integer.class) {
            field.set(instance, (int) Double.parseDouble(raw));
        } else if (fieldType == long.class || fieldType == Long.class) {
            field.set(instance, (long) Double.parseDouble(raw));
        } else if (fieldType == double.class || fieldType == Double.class) {
            field.set(instance, Double.parseDouble(raw));
        } else if (fieldType == boolean.class || fieldType == Boolean.class) {
            field.set(instance, Boolean.parseBoolean(raw));
        } else if (fieldType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<Enum>) fieldType, raw);
            field.set(instance, enumValue);
        } else {
            throw new TestDataLoadException(
                    "Unsupported field type '" + fieldType.getSimpleName() + "' for field '" + field.getName() + "'");
        }
    }
}