package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public final class OutboxInboxTestSupport {

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("insurance_test")
            .withUsername("test")
            .withPassword("test");

    public static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    public static final long DEFAULT_ADVISORY_LOCK_KEY = 42_001L;

    private OutboxInboxTestSupport() {
    }

    public static void startContainers() {
        POSTGRES.start();
        KAFKA.start();
    }

    public static DataSource newDataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }

    public static void applySchema(JdbcTemplate jdbcTemplate) throws IOException {
        String ddl = new ClassPathResource("db/outbox-inbox.sql").getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(ddl);
    }

    public static void truncateTables(JdbcTemplate jdbcTemplate) {
        jdbcTemplate.execute("TRUNCATE TABLE outbox RESTART IDENTITY");
        jdbcTemplate.execute("TRUNCATE TABLE processed_events");
    }

    public static String freshTopicName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    public static void createTopic(String topicName, int partitions) {
        Map<String, Object> config = Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(config)) {
            admin.createTopics(List.of(new NewTopic(topicName, partitions, (short) 1))).all().get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static KafkaTemplate<String, String> newKafkaTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    public static KafkaConsumer<String, String> newConsumer(String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    public static List<ConsumerRecord<String, String>> consumeUntilQuiet(
            KafkaConsumer<String, String> consumer, int expectedCount) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long quietDeadlineNanos = 0L;
        while (true) {
            var polled = consumer.poll(Duration.ofMillis(250));
            if (!polled.isEmpty()) {
                polled.forEach(records::add);
            }
            if (records.size() >= expectedCount && quietDeadlineNanos == 0L) {
                quietDeadlineNanos = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            }
            if (quietDeadlineNanos != 0L && System.nanoTime() >= quietDeadlineNanos) {
                return records;
            }
            if (quietDeadlineNanos == 0L && records.isEmpty()) {
                continue;
            }
            if (quietDeadlineNanos == 0L && records.size() < expectedCount) {
                continue;
            }
        }
    }

}
