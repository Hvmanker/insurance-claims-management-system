package com.sunil.insurance.settlement.messaging;

import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.settlement.service.SettlementService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_APPROVED;

@Component
public class ClaimApprovedListener {
    private final SettlementService settlementService;

    public ClaimApprovedListener(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @KafkaListener(topics = CLAIM_APPROVED, groupId = "settlement-service")
    public void onClaimApproved(ClaimApprovedEvent event) {
        settlementService.settle(event);
    }
}

