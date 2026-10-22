package com.hvmanker.insurance.common;

public final class KafkaTopics {
    public static final String CLAIM_SUBMITTED = "claim.submitted";
    public static final String CLAIM_APPROVED = "claim.approved";
    public static final String CLAIM_REJECTED = "claim.rejected";
    public static final String CLAIM_SETTLED = "claim.settled";
    public static final String CLAIM_ANALYSIS_REQUESTED = "claim.analysis.requested";
    public static final String CLAIM_ANALYSIS_COMPLETED = "claim.analysis.completed";
    public static final String CLAIM_MANUAL_DECISION = "claim.manual-decision";

    private KafkaTopics() {
    }
}
