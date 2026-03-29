package com.amazon.tests.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Test utility for consuming and asserting Kafka messages.
 *
 * Strategy: connects a real Kafka consumer to a test topic,
 * polls messages, deserialises to JsonNode, and matches by
 * user-supplied predicate with configurable timeout.
 */
@Slf4j
public class KafkaTestConsumer implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;
    private final ObjectMapper objectMapper;
    private static final String BOOTSTRAP_SERVERS = System.getProperty(
            "kafka.bootstrap.servers", "localhost:9092");

    public KafkaTestConsumer(String... topics) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "50");

        this.consumer = new KafkaConsumer<>(props);
       // this.consumer.subscribe(Arrays.asList(topics));
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        // Initial dummy poll to trigger partition assignment
        //consumer.poll(Duration.ofMillis(500));

        // ✅ Manual partition assignment
        List<TopicPartition> partitions = new ArrayList<>();
        for (String topic : topics) {
            consumer.partitionsFor(topic).forEach(info ->
                    partitions.add(new TopicPartition(info.topic(), info.partition()))
            );
        }
        consumer.assign(partitions);

        // Seek to beginning of all partitions to consume all messages
        consumer.seekToBeginning(partitions);
        log.info("KafkaTestConsumer subscribed to topics: {}", Arrays.toString(topics));
    }

    /**
     * Poll until a message matching the predicate arrives, or timeout.
     */
    public Optional<JsonNode> waitForMessage(Predicate<JsonNode> predicate, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = objectMapper.readTree(record.value());
                    log.debug("Received message from topic={} key={}: {}",
                            record.topic(), record.key(), record.value());

                    if (predicate.test(node)) {
                        log.info("✅ Matching message found on topic={} key={}",
                                record.topic(), record.key());
                        return Optional.of(node);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse message: {}", record.value());
                }
            }
        }

        log.warn("⏰ No matching message found within {}s", timeoutSeconds);
        return Optional.empty();
    }

    /**
     * Collect all messages matching predicate within timeout.
     */
    public List<JsonNode> collectMessages(Predicate<JsonNode> predicate, int timeoutSeconds) {
        List<JsonNode> results = new ArrayList<>();
        long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);

        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, String> record : records) {
                try {
                    JsonNode node = objectMapper.readTree(record.value());
                    if (predicate.test(node)) {
                        results.add(node);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse message: {}", e.getMessage());
                }
            }
        }
        return results;
    }

    /**
     * Count messages on a topic matching predicate within timeout.
     */
    public int countMessages(Predicate<JsonNode> predicate, int timeoutSeconds) {
        return collectMessages(predicate, timeoutSeconds).size();
    }

    /**
     * Check a field value in the JsonNode.
     */
    public static Predicate<JsonNode> hasField(String field, String value) {
        return node -> node.has(field) && value.equals(node.get(field).asText());
    }

    /**
     * Check event type field.
     */
    public static Predicate<JsonNode> isEventType(String eventType) {
        return hasField("eventType", eventType);
    }

    @Override
    public void close() {
        try {
            consumer.close();
            log.info("KafkaTestConsumer closed.");
        } catch (Exception e) {
            log.warn("Error closing KafkaTestConsumer: {}", e.getMessage());
        }
    }

    public void seekToBeginning() {
        // Ensure partitions are assigned
        consumer.poll(Duration.ofMillis(100));

        Set<TopicPartition> partitions = consumer.assignment();

        if (partitions.isEmpty()) {
            throw new IllegalStateException("No partitions assigned to consumer");
        }

        log.info("Seeking to beginning for partitions: {}", partitions);
        consumer.seekToBeginning(partitions);
    }
}
