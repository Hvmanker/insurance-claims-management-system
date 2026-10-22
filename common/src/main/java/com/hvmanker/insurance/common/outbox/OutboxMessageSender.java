package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends outbox rows to Kafka. The message key is {@code aggregate_id} (the claim id) as a string
 * so all events for a claim stay ordered on one partition.
 */
@FunctionalInterface
public interface OutboxMessageSender {

    String EVENT_ID_HEADER = "eventId";

    void send(OutboxRecord record) throws InterruptedException, ExecutionException, TimeoutException;

    static OutboxMessageSender kafka(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        return record -> {
            ProducerRecord<String, String> message = new ProducerRecord<>(
                    record.topic(),
                    record.aggregateId().toString(),
                    record.payload());
            message.headers().add(new RecordHeader(
                    EVENT_ID_HEADER,
                    record.eventId().toString().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(message).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        };
    }
}
