package com.amazon.tests.factory.excel;


import org.apache.poi.ss.usermodel.Row;

public class ExcelParser {

    public <T> T parse(Row row, Class<T> clazz) {

        try {

            T instance = clazz.getDeclaredConstructor().newInstance();

            // Reflection mapping goes here
            //
            // Column "id"    -> user.setId(...)
            // Column "name"  -> user.setName(...)
            // Column "email" -> user.setEmail(...)

            return instance;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to parse Excel row into "
                            + clazz.getSimpleName(),
                    e);

        }
    }
}