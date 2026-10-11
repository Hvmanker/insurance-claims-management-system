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
