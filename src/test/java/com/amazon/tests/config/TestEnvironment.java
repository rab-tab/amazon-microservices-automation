package com.amazon.tests.config;

import com.amazon.tests.models.TestModels;
import com.amazon.tests.utils.kafka.KafkaTestConsumer;
import com.amazon.tests.utils.kafka.KafkaTestProducer;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
public class TestEnvironment {
    private TestModels.UserResponse user;
    private String userToken;

    private TestModels.UserResponse seller;
    private String sellerToken;

    private TestModels.ProductResponse product;

    private KafkaTestProducer kafkaProducer;

    private final Map<String, KafkaTestConsumer> consumers = new HashMap<>();


}
