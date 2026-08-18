package com.amazon.tests.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class JsonTestDataAdapter<T> implements TestDataSource<T> {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public List<T> loadData(String sourceLocation, Class<T> type) {
        File file = new File(sourceLocation);
        if (!file.exists()) {
            throw new TestDataLoadException("JSON test data file not found: " + sourceLocation);
        }
        try {
            CollectionType listType = mapper.getTypeFactory()
                    .constructCollectionType(List.class, type);
            return mapper.readValue(file, listType);
        } catch (IOException e) {
            throw new TestDataLoadException("Failed to parse JSON test data: " + sourceLocation, e);
        }
    }
}

