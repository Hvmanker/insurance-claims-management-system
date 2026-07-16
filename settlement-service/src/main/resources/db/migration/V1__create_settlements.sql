CREATE TABLE settlements (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL UNIQUE,
    policy_number VARCHAR(40) NOT NULL,
    paid_amount NUMERIC(12, 2) NOT NULL CHECK (paid_amount > 0),
    settled_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_settlements_policy_number ON settlements(policy_number);

