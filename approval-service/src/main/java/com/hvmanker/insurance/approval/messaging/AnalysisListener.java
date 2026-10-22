package com.hvmanker.insurance.approval.messaging;

import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent;
import com.hvmanker.insurance.common.logging.EventMdc;
import com.hvmanker.insurance.common.outbox.OutboxMessageSender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_ANALYSIS_COMPLETED;

@Component
public class AnalysisListener {
    private final ApprovalService approvalService;

    public AnalysisListener(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @KafkaListener(
            topics = CLAIM_ANALYSIS_COMPLETED,
            groupId = "approval-service-analysis-completed",
            properties = {
                    "spring.json.value.default.type=com.hvmanker.insurance.common.events.ClaimAnalysisCompletedEvent"
            })
    @Transactional
    public void onAnalysisCompleted(ConsumerRecord<String, ClaimAnalysisCompletedEvent> record) {
        ClaimAnalysisCompletedEvent event = record.value();
        UUID eventId = resolveEventId(record);
        try (EventMdc ignored = EventMdc.open(event.claimId(), eventId)) {
            approvalService.handleAnalysisCompleted(event);
        }
    }

    private UUID resolveEventId(ConsumerRecord<String, ClaimAnalysisCompletedEvent> record) {
        Header header = record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER);
        if (header != null && header.value() != null && header.value().length > 0) {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        }
        return record.value().eventId();
    }
}
