package com.sunil.insurance.settlement.service;

import com.sunil.insurance.common.events.ClaimApprovedEvent;
import com.sunil.insurance.common.events.ClaimSettledEvent;
import com.sunil.insurance.settlement.domain.Settlement;
import com.sunil.insurance.settlement.domain.SettlementRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static com.sunil.insurance.common.KafkaTopics.CLAIM_SETTLED;

@Service
public class SettlementService {
    private final SettlementRepository settlementRepository;
    private final KafkaTemplate<String, ClaimSettledEvent> kafkaTemplate;

    public SettlementService(SettlementRepository settlementRepository, KafkaTemplate<String, ClaimSettledEvent> kafkaTemplate) {
        this.settlementRepository = settlementRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional
    public void settle(ClaimApprovedEvent event) {
        settlementRepository.findByClaimId(event.claimId()).ifPresentOrElse(
                existing -> publish(existing),
                () -> {
                    Settlement settlement = settlementRepository.save(new Settlement(
                            UUID.randomUUID(),
                            event.claimId(),
                            event.policyNumber(),
                            event.approvedAmount(),
                            Instant.now()
                    ));
                    publish(settlement);
                }
        );
    }

    private void publish(Settlement settlement) {
        kafkaTemplate.send(CLAIM_SETTLED, settlement.getClaimId().toString(), new ClaimSettledEvent(
                settlement.getClaimId(),
                settlement.getId(),
                settlement.getPolicyNumber(),
                settlement.getPaidAmount(),
                settlement.getSettledAt()
        ));
    }
}

