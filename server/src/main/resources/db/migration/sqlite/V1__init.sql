-- Tsumugi sync server schema (docs/SYNC_PROTOCOL.md). Keep in step with ../postgres/V1__init.sql (only the blob column type differs).
-- Times are epoch milliseconds; ids are UUID strings.

CREATE TABLE users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    display_name TEXT,
    email_verified INTEGER NOT NULL DEFAULT 0,
    verify_token TEXT,
    e2e_enabled INTEGER NOT NULL DEFAULT 0,
    e2e_salt TEXT,
    leaderboard_opt_in INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);

CREATE TABLE devices (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    platform TEXT NOT NULL,
    packs TEXT NOT NULL DEFAULT '{}',
    last_seen_at BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);
CREATE INDEX devices_user ON devices(user_id);

-- Refresh tokens are stored hashed. A family is one login; reusing a rotated token revokes the whole family.
CREATE TABLE refresh_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    family_id TEXT NOT NULL,
    expires_at BIGINT NOT NULL,
    used INTEGER NOT NULL DEFAULT 0,
    revoked INTEGER NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
);
CREATE INDEX refresh_tokens_family ON refresh_tokens(family_id);

-- Per-user sequence counter; bumped in the same transaction as the inserted changes.
CREATE TABLE seq_counters (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    last_seq BIGINT NOT NULL
);

-- The append-only change log. row_json (clear) or sealed (E2E ciphertext) is stored opaquely.
CREATE TABLE changes (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    seq BIGINT NOT NULL,
    tbl TEXT NOT NULL,
    row_key TEXT NOT NULL,
    op TEXT NOT NULL,
    row_json TEXT,
    sealed TEXT,
    updated_at BIGINT NOT NULL,
    device_id TEXT NOT NULL,
    dedupe TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, seq)
);
CREATE UNIQUE INDEX changes_dedupe ON changes(user_id, dedupe);

-- Clear-text review facts (id + time) for the opt-in leaderboard. Never filled for E2E users.
CREATE TABLE review_facts (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    review_id TEXT NOT NULL,
    ts BIGINT NOT NULL,
    PRIMARY KEY (user_id, review_id)
);
CREATE INDEX review_facts_ts ON review_facts(ts);

CREATE TABLE blobs (
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    id TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size BIGINT NOT NULL,
    data BLOB NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (user_id, id)
);

-- WebAuthn credentials (the serialized attested credential data from webauthn4j) and pending challenges.
CREATE TABLE passkeys (
    credential_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    credential TEXT NOT NULL,
    sign_count BIGINT NOT NULL,
    created_at BIGINT NOT NULL
);

CREATE TABLE webauthn_challenges (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    challenge TEXT NOT NULL,
    kind TEXT NOT NULL,
    expires_at BIGINT NOT NULL
);
