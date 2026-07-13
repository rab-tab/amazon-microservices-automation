package com.amazon.tests.config;


import java.sql.ResultSet;

public class ResultMapper {

    public <T> T map(ResultSet resultSet,
                     Class<T> clazz) {

        try {

            T instance = clazz.getDeclaredConstructor().newInstance();

            // Reflection mapping logic goes here.
            // Example:
            //
            // User.id <- resultSet.getLong("id")
            // User.name <- resultSet.getString("name")
            //
            // Can later use Jackson / BeanUtils / MapStruct.

            return instance;

        } catch (Exception e) {

            throw new RuntimeException(
                    "Unable to map database result to "
                            + clazz.getSimpleName(),
                    e);

        }
    }

}