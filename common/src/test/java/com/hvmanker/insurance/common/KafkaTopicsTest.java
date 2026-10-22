package com.hvmanker.insurance.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaTopicsTest {

    @Test
    void analysisTopicNames() {
        assertEquals("claim.analysis.requested", KafkaTopics.CLAIM_ANALYSIS_REQUESTED);
        assertEquals("claim.analysis.completed", KafkaTopics.CLAIM_ANALYSIS_COMPLETED);
        assertEquals("claim.submitted", KafkaTopics.CLAIM_SUBMITTED);
        assertEquals("claim.approved", KafkaTopics.CLAIM_APPROVED);
        assertEquals("claim.rejected", KafkaTopics.CLAIM_REJECTED);
        assertEquals("claim.settled", KafkaTopics.CLAIM_SETTLED);
    }
}
