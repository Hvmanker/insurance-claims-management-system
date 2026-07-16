CREATE TABLE claim_approvals (
    claim_id UUID PRIMARY KEY,
    policy_number VARCHAR(40) NOT NULL,
    requested_amount NUMERIC(12, 2) NOT NULL CHECK (requested_amount > 0),
    approved_amount NUMERIC(12, 2),
    decision VARCHAR(20) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_claim_approvals_decision_decided_at ON claim_approvals(decision, decided_at DESC);

