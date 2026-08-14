CREATE TABLE portal_users (
    id UUID PRIMARY KEY,
    email VARCHAR(160) NOT NULL UNIQUE,
    full_name VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE claims ADD COLUMN reason VARCHAR(1000);
ALTER TABLE claims ADD COLUMN owner_id UUID;
UPDATE claims SET reason = 'Claim submitted before reason capture was introduced' WHERE reason IS NULL;
ALTER TABLE claims ALTER COLUMN reason SET NOT NULL;
CREATE INDEX idx_claims_owner_submitted_at ON claims(owner_id, submitted_at DESC);

INSERT INTO portal_users (id, email, full_name, password_hash, role, created_at)
VALUES ('11111111-1111-1111-1111-111111111111', 'agent@insureflow.local', 'Morgan Rivera', '$2y$12$i3NQl102PxBSE6jorj7mKO2HhdlxFT05SWRSWtLPcj/fe2IuvvC9W', 'AGENT', CURRENT_TIMESTAMP);
