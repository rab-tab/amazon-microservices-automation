package com.amazon.tests.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Builder;
import lombok.Getter;

import java.util.Map;

@Getter
@Builder
public class ServiceResponse {
    private final int statusCode;
    private final String body;
    private final Map<String, String> headers;
    private final Map<String, Object> attributes;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public <T> T as(Class<T> type) {
        try {
            return MAPPER.readValue(this.body, type);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to deserialize response body to " + type.getSimpleName() + ". Body: " + body, e);
        }
    }

    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}