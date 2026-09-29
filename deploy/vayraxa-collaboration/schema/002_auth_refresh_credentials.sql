BEGIN;

CREATE TABLE IF NOT EXISTS collaboration_user_credentials (
    user_id UUID PRIMARY KEY REFERENCES collaboration_users(id) ON DELETE CASCADE,
    password_hash BYTEA NOT NULL,
    password_salt BYTEA NOT NULL,
    password_algorithm TEXT NOT NULL DEFAULT 'SCRYPT_V1'
        CHECK (password_algorithm IN ('SCRYPT_V1')),
    failed_attempts INTEGER NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
    locked_until TIMESTAMPTZ,
    password_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE collaboration_api_tokens
ADD COLUMN IF NOT EXISTS token_family_id UUID;

ALTER TABLE collaboration_api_tokens
ADD COLUMN IF NOT EXISTS replaced_by_token_id UUID REFERENCES collaboration_api_tokens(id) ON DELETE SET NULL;

ALTER TABLE collaboration_api_tokens
ADD COLUMN IF NOT EXISTS revoked_reason TEXT;

CREATE INDEX IF NOT EXISTS idx_collaboration_token_family
ON collaboration_api_tokens(token_family_id, token_kind, revoked_at);

CREATE INDEX IF NOT EXISTS idx_collaboration_credentials_lock
ON collaboration_user_credentials(locked_until)
WHERE locked_until IS NOT NULL;

COMMIT;
