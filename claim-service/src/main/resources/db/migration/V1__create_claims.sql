CREATE TABLE claims (
    id UUID PRIMARY KEY,
    policy_number VARCHAR(40) NOT NULL,
    claimant_name VARCHAR(120) NOT NULL,
    claim_type VARCHAR(40) NOT NULL,
    estimated_amount NUMERIC(12, 2) NOT NULL CHECK (estimated_amount > 0),
    status VARCHAR(20) NOT NULL,
    submitted_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_claims_policy_number ON claims(policy_number);
CREATE INDEX idx_claims_status_submitted_at ON claims(status, submitted_at DESC);

