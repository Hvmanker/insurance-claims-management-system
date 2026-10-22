package com.hvmanker.insurance.approval.domain;

public final class WorkflowStatus {
    public static final String ANALYSIS_PENDING = "ANALYSIS_PENDING";
    public static final String REVIEW_READY = "REVIEW_READY";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    private WorkflowStatus() {
    }
}
