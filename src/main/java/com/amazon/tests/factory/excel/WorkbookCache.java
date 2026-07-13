package com.amazon.tests.factory.excel;



import org.apache.poi.ss.usermodel.Workbook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorkbookCache {

    private final Map<String, Workbook> cache =
            new ConcurrentHashMap<>();

    public Workbook get(String filePath) {
        return cache.get(filePath);
    }

    public void put(String filePath, Workbook workbook) {
        cache.put(filePath, workbook);
    }

    public boolean contains(String filePath) {
        return cache.containsKey(filePath);
    }

    public void clear() {
        cache.clear();
    }
}
