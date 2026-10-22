package com.hvmanker.insurance.common.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@FunctionalInterface
public interface OutboxMessageSender {

    String EVENT_ID_HEADER = "eventId";

    void send(OutboxRecord record) throws InterruptedException, ExecutionException, TimeoutException;

    static OutboxMessageSender kafka(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        return record -> {
            String claimIdKey = record.aggregateId().toString();
            ProducerRecord<String, String> message = new ProducerRecord<>(
                    record.topic(),
                    claimIdKey,
                    record.payload());
            message.headers().add(new RecordHeader(
                    EVENT_ID_HEADER,
                    record.eventId().toString().getBytes(StandardCharsets.UTF_8)));
            kafkaTemplate.send(message).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        };
    }
}
