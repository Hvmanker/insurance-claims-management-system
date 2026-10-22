package com.hvmanker.insurance.approval.messaging;

import com.hvmanker.insurance.approval.service.ApprovalService;
import com.hvmanker.insurance.common.events.ClaimSubmittedEvent;
import com.hvmanker.insurance.common.logging.EventMdc;
import com.hvmanker.insurance.common.outbox.OutboxMessageSender;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.hvmanker.insurance.common.KafkaTopics.CLAIM_SUBMITTED;

@Component
public class ClaimSubmittedListener {
    private static final Logger log = LoggerFactory.getLogger(ClaimSubmittedListener.class);

    private final ApprovalService approvalService;

    public ClaimSubmittedListener(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @KafkaListener(topics = CLAIM_SUBMITTED, groupId = "approval-service")
    public void onClaimSubmitted(ConsumerRecord<String, ClaimSubmittedEvent> record) {
        ClaimSubmittedEvent event = record.value();
        UUID eventId = resolveEventId(record, event);
        try (EventMdc ignored = EventMdc.open(event.claimId(), eventId)) {
            approvalService.handleClaimSubmitted(event, eventId);
        }
    }

    private UUID resolveEventId(ConsumerRecord<String, ClaimSubmittedEvent> record, ClaimSubmittedEvent event) {
        Header header = record.headers().lastHeader(OutboxMessageSender.EVENT_ID_HEADER);
        if (header != null && header.value() != null && header.value().length > 0) {
            return UUID.fromString(new String(header.value(), StandardCharsets.UTF_8));
        }
        log.warn("claim.submitted without eventId claimId={}", event.claimId());
        return UUID.nameUUIDFromBytes(
                ("claim.submitted:" + event.claimId()).getBytes(StandardCharsets.UTF_8));
    }
}
