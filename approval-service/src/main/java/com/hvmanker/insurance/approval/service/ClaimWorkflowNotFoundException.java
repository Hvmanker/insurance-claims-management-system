package com.hvmanker.insurance.approval.service;

/**
 * Thrown when a decision targets a claim with no workflow row yet.
 * HTTP clients receive 404; the interim manual-decision listener treats this as retryable.
 */
public class ClaimWorkflowNotFoundException extends RuntimeException {
}
