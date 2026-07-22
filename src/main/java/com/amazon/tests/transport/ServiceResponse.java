package com.amazon.tests.transport;


import com.amazon.tests.utils.retry.RetryableResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
public class ServiceResponse implements RetryableResponse  {

        private final int statusCode;
        private final String body;   // must be String, not Object
        private final Map<String, String> headers;
        private final Map<String, Object> attributes;

        public <T> T as(Class<T> type) {
            try {
                return new ObjectMapper().readValue(this.body, type);  // explicit `this.body`
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to deserialize response body to " + type.getSimpleName() + ". Body: " + body, e);
            }
        }
    }
