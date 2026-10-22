package com.hvmanker.insurance.approval.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent;
import com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent;
import com.hvmanker.insurance.common.inbox.InboxGuard;
import com.hvmanker.insurance.common.logging.EventMdc;
import com.hvmanker.insurance.common.outbox.OutboxMessageSender;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_COMPLETED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;

@Component
public class RulesOnlyAnalyser {
    private static final Logger log = LoggerFactory.getLogger(RulesOnlyAnalyser.class);
    private static final String ANALYSER_CONSUMER_GROUP = "approval-service-rules-analyser";

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final InboxGuard inboxGuard;
    private final TransactionTemplate transactionTemplate;
    private final long analyserDelayMs;
    private final boolean enabled;

    public RulesOnlyAnalyser(
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper,
            InboxGuard inboxGuard,
            TransactionTemplate transactionTemplate,
            @Value("${approval.analyser.delay-ms:0}") long analyserDelayMs,
            @Value("${approval.analyser.enabled:true}") boolean enabled) {
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.inboxGuard = inboxGuard;
        this.transactionTemplate = transactionTemplate;
        this.analyserDelayMs = analyserDelayMs;
        this.enabled = enabled;
    }

    @KafkaListener(
            topics = CLAIM_ANALYSIS_REQUESTED,
            groupId = ANALYSER_CONSUMER_GROUP,
            autoStartup = "${approval.analyser.enabled:true}",
            properties = {
                    "spring.json.value.default.type=com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent"
            })
    public void onAnalysisRequested(ConsumerRecord<String, ClaimAnalysisRequestedEvent> record) {
        if (!enabled) {
            return;
        }
        ClaimAnalysisRequestedEvent event = record.value();
        UUID eventId = resolveEventId(record);
        try (EventMdc ignored = EventMdc.open(event.claimId(), eventId)) {
            if (analyserDelayMs > 0) {
                try {
                    Thread.sleep(analyserDelayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during analyser delay", interrupted);
                }
            }
            transactionTemplate.executeWithoutResult(status -> {
                if (!inboxGuard.firstTime(ANALYSER_CONSUMER_GROUP, eventId)) {
                    EventMdc.logDuplicateSkipped(log, event.claimId(), eventId);
                    return;
                }
                publishAnalysisCompleted(event);
            });
        }
    }

    private void publishAnalysisCompleted(ClaimAnalysisRequestedEvent event) {
        List<String> flags = List.of();
        UUID completedEventId = UUID.randomUUID();
        ClaimAnalysisCompletedEvent completed =
                new ClaimAnalysisCompletedEvent(completedEventId, event.claimId(), flags);
        try {
            outboxWriter.append(
                    completedEventId,
                    event.claimId(),
                    CLAIM_ANALYSIS_COMPLETED,
                    objectMapper.writeValueAsString(completed));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize claim.analysis.completed payload", exception);
        }
        log.debug("rules analyser completed claimId={} flags={}", event.claimId(), flags);
    }

    private UUID resolveEventId(ConsumerRecord<String, ClaimAnalysisRequestedEvent> record) {
        Header header = record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER);
        if (header != null && header.value() != null && header.value().length > 0) {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        }
        return record.value().eventId();
    }
}
