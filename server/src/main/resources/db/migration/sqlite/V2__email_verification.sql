-- Phase 14 (BRIEF_V2, DECISIONS D-310/D-311): email verification is required before sync.
-- Links now expire, and resends are rate limited by the time of the last email.
ALTER TABLE users ADD COLUMN verify_expires_at BIGINT;
ALTER TABLE users ADD COLUMN verify_sent_at BIGINT;

-- Accounts created before verification was required keep syncing: they were told it was optional (D-028).
UPDATE users SET email_verified = 1;
