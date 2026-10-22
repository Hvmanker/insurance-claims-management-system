CREATE TABLE claim_workflow (
    claim_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    policy_number VARCHAR(40) NOT NULL,
    claimed_amount NUMERIC(12, 2) NOT NULL CHECK (claimed_amount > 0),
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE claim_approvals
    ADD COLUMN decision_id UUID,
    ADD COLUMN decided_by_user_id VARCHAR(128),
    ADD COLUMN decided_by_role VARCHAR(32),
    ADD COLUMN source VARCHAR(16);

CREATE UNIQUE INDEX idx_claim_approvals_decision_id ON claim_approvals (decision_id);

ALTER TABLE claim_approvals
    ADD CONSTRAINT chk_claim_approvals_source CHECK (source IS NULL OR source IN ('AUTO', 'HUMAN'));

CREATE TABLE decision_audit (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL,
    actor_user_id VARCHAR(128) NOT NULL,
    actor_role VARCHAR(32) NOT NULL,
    attempted_decision VARCHAR(20) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_decision_audit_claim_id ON decision_audit (claim_id);

CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID UNIQUE NOT NULL,
    aggregate_id UUID NOT NULL,
    topic TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ NULL
);

CREATE TABLE processed_events (
    consumer_group TEXT NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
