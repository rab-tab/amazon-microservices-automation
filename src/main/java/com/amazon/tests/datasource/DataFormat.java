package com.amazon.tests.datasource;

public enum DataFormat {
    JSON, EXCEL, DB;

    public static DataFormat fromExtension(String path) {
        String lower = path.toLowerCase();
        if (lower.endsWith(".json")) return JSON;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return EXCEL;
        throw new IllegalArgumentException("Cannot infer DataFormat from: " + path);
    }
}
