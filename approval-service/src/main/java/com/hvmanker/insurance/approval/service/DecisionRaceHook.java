package com.hvmanker.insurance.approval.service;

import com.hvmanker.insurance.approval.domain.ApprovalSource;
import org.springframework.stereotype.Component;

/**
 * Test seam for case-2 latch races. Production implementation is a no-op.
 */
@Component
public class DecisionRaceHook {
    public void beforeConditionalUpdate(ApprovalSource source) {
    }
}
