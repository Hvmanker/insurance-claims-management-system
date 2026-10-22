package com.hvmanker.insurance.approval.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent;
import com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent;
import com.hvmanker.insurance.common.outbox.OutboxWriter;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_COMPLETED;
import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_REQUESTED;

@Component
public class RulesOnlyAnalyser {
    private static final Logger log = LoggerFactory.getLogger(RulesOnlyAnalyser.class);

    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final long analyserDelayMs;
    private final boolean analyserEnabled;

    public RulesOnlyAnalyser(
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper,
            @Value("${approval.analyser.delay-ms:0}") long analyserDelayMs,
            @Value("${approval.analyser.enabled:true}") boolean analyserEnabled) {
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.analyserDelayMs = analyserDelayMs;
        this.analyserEnabled = analyserEnabled;
    }

    @KafkaListener(
            topics = CLAIM_ANALYSIS_REQUESTED,
            groupId = "approval-service-rules-analyser",
            properties = {
                    "spring.json.value.default.type=com.hvmanker.insurance.common.events.ClaimAnalysisRequestedEvent"
            })
    @Transactional
    public void onAnalysisRequested(ConsumerRecord<String, ClaimAnalysisRequestedEvent> record) {
        if (!analyserEnabled) {
            return;
        }
        ClaimAnalysisRequestedEvent event = record.value();
        if (analyserDelayMs > 0) {
            try {
                Thread.sleep(analyserDelayMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during analyser delay", interrupted);
            }
        }
        List<String> flags = evaluateRules(event);
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

    List<String> evaluateRules(ClaimAnalysisRequestedEvent event) {
        return List.of();
    }
}
