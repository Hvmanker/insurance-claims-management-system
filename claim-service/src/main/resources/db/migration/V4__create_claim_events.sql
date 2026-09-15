CREATE TABLE claim_events (
    id UUID PRIMARY KEY,
    claim_id UUID NOT NULL REFERENCES claims(id),
    event_type VARCHAR(40) NOT NULL,
    title VARCHAR(120) NOT NULL,
    detail VARCHAR(1000) NOT NULL,
    actor VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_claim_events_claim_occurred_at ON claim_events(claim_id, occurred_at ASC);
