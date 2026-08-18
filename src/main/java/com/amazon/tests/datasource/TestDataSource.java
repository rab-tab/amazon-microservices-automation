package com.amazon.tests.datasource;

import java.util.List;

public interface TestDataSource<T> {
    List<T> loadData(String sourceLocation, Class<T> type);
}
