BEGIN;

CREATE TABLE IF NOT EXISTS collaboration_users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    display_name TEXT NOT NULL,
    auth_subject TEXT UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED','ACTIVE','DISABLED','DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS collaboration_roles (
    id TEXT PRIMARY KEY,
    role_code TEXT NOT NULL UNIQUE,
    role_name TEXT NOT NULL,
    description TEXT
);

CREATE TABLE IF NOT EXISTS collaboration_permissions (
    id TEXT PRIMARY KEY,
    permission_code TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS collaboration_role_permissions (
    role_id TEXT NOT NULL REFERENCES collaboration_roles(id) ON DELETE CASCADE,
    permission_id TEXT NOT NULL REFERENCES collaboration_permissions(id) ON DELETE CASCADE,
    PRIMARY KEY(role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS collaboration_project_members (
    project_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE CASCADE,
    role_id TEXT NOT NULL REFERENCES collaboration_roles(id) ON DELETE RESTRICT,
    discipline_code TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('INVITED','ACTIVE','SUSPENDED','REMOVED')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id, user_id)
);

CREATE TABLE IF NOT EXISTS collaboration_api_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE CASCADE,
    token_fingerprint CHAR(64) NOT NULL UNIQUE,
    token_kind TEXT NOT NULL DEFAULT 'ACCESS' CHECK (token_kind IN ('ACCESS','REFRESH','SERVICE')),
    client_id TEXT NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_collaboration_tokens_active
ON collaboration_api_tokens(token_fingerprint, expires_at)
WHERE revoked_at IS NULL;

CREATE TABLE IF NOT EXISTS collaboration_user_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE CASCADE,
    client_id TEXT NOT NULL,
    device_name TEXT,
    client_version TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','REVOKED','EXPIRED')),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS collaboration_project_presence (
    project_id UUID NOT NULL,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE CASCADE,
    session_id UUID NOT NULL REFERENCES collaboration_user_sessions(id) ON DELETE CASCADE,
    presence_state TEXT NOT NULL DEFAULT 'VIEWING' CHECK (presence_state IN ('VIEWING','EDITING','AWAY','DISCONNECTED')),
    opened_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    closed_at TIMESTAMPTZ,
    PRIMARY KEY(project_id, session_id)
);

CREATE TABLE IF NOT EXISTS collaboration_object_leases (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE CASCADE,
    session_id UUID REFERENCES collaboration_user_sessions(id) ON DELETE SET NULL,
    lease_scope TEXT NOT NULL DEFAULT 'EDIT' CHECK (lease_scope IN ('EDIT','REVIEW','ADMIN')),
    lease_token UUID NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RELEASED','EXPIRED','FORCED')),
    acquired_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    heartbeat_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    lease_version BIGINT NOT NULL DEFAULT 1 CHECK (lease_version >= 1)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_collaboration_active_lease
ON collaboration_object_leases(project_id, object_type, object_id)
WHERE status = 'ACTIVE';

CREATE TABLE IF NOT EXISTS collaboration_object_versions (
    project_id UUID NOT NULL,
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    object_version BIGINT NOT NULL DEFAULT 1 CHECK (object_version >= 1),
    last_transaction_id UUID,
    modified_by UUID REFERENCES collaboration_users(id) ON DELETE SET NULL,
    modified_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id, object_type, object_id)
);

CREATE TABLE IF NOT EXISTS collaboration_project_sequences (
    project_id UUID PRIMARY KEY,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0)
);

CREATE TABLE IF NOT EXISTS collaboration_transactions (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    client_transaction_id TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE RESTRICT,
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    operation TEXT NOT NULL CHECK (operation IN ('CREATE','UPDATE','DELETE','ATTACH_BLOB','DETACH_BLOB')),
    base_version BIGINT NOT NULL DEFAULT 0 CHECK (base_version >= 0),
    resulting_version BIGINT NOT NULL CHECK (resulting_version >= 0),
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, client_transaction_id)
);

CREATE TABLE IF NOT EXISTS collaboration_change_events (
    project_id UUID NOT NULL,
    server_sequence BIGINT NOT NULL CHECK (server_sequence > 0),
    transaction_id UUID NOT NULL REFERENCES collaboration_transactions(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES collaboration_users(id) ON DELETE RESTRICT,
    object_type TEXT NOT NULL,
    object_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    base_version BIGINT NOT NULL,
    resulting_version BIGINT NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(project_id, server_sequence),
    UNIQUE(transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_collaboration_events_object
ON collaboration_change_events(project_id, object_type, object_id, server_sequence);

CREATE TABLE IF NOT EXISTS collaboration_blob_refs (
    blob_id UUID PRIMARY KEY,
    project_id UUID NOT NULL,
    object_type TEXT,
    object_id TEXT,
    object_key TEXT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0),
    media_type TEXT,
    storage_url TEXT,
    created_by UUID REFERENCES collaboration_users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(project_id, object_key)
);

INSERT INTO collaboration_permissions(id, permission_code, description) VALUES
('PERM_PROJECT_VIEW','project.view','Open and view a project'),
('PERM_PROJECT_EDIT','project.edit','Modify project engineering data'),
('PERM_PROJECT_MANAGE_MEMBERS','project.manage_members','Manage project membership and project roles'),
('PERM_OBJECT_CREATE','object.create','Create engineering objects'),
('PERM_OBJECT_EDIT','object.edit','Edit engineering objects'),
('PERM_OBJECT_DELETE','object.delete','Delete engineering objects'),
('PERM_LOCK_ACQUIRE','lock.acquire','Acquire edit leases'),
('PERM_LOCK_FORCE_RELEASE','lock.force_release','Force-release another user lease'),
('PERM_AUDIT_VIEW','audit.view','View project audit history')
ON CONFLICT (id) DO NOTHING;

INSERT INTO collaboration_roles(id, role_code, role_name, description) VALUES
('ROLE_PROJECT_OWNER','PROJECT_OWNER','Project Owner','Full project authority'),
('ROLE_PROJECT_ADMIN','PROJECT_ADMIN','Project Administrator','Project administration and engineering authority'),
('ROLE_PROJECT_MANAGER','PROJECT_MANAGER','Project Manager','Project delivery and administration authority'),
('ROLE_PROJECT_LEAD','PROJECT_LEAD','Discipline Lead','Discipline engineering authority'),
('ROLE_PROJECT_DESIGNER','PROJECT_DESIGNER','Designer','Engineering authoring authority'),
('ROLE_PROJECT_REVIEWER','PROJECT_REVIEWER','Reviewer','Review authority'),
('ROLE_PROJECT_VIEWER','PROJECT_VIEWER','Viewer','Read-only access')
ON CONFLICT (id) DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_OWNER', id FROM collaboration_permissions
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_ADMIN', id FROM collaboration_permissions
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_MANAGER', id FROM collaboration_permissions
WHERE permission_code IN (
    'project.view','project.edit','project.manage_members','object.create','object.edit','object.delete',
    'lock.acquire','lock.force_release','audit.view'
)
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_LEAD', id FROM collaboration_permissions
WHERE permission_code IN (
    'project.view','project.edit','object.create','object.edit','object.delete','lock.acquire','audit.view'
)
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_DESIGNER', id FROM collaboration_permissions
WHERE permission_code IN ('project.view','project.edit','object.create','object.edit','object.delete','lock.acquire')
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_REVIEWER', id FROM collaboration_permissions
WHERE permission_code IN ('project.view','audit.view')
ON CONFLICT DO NOTHING;

INSERT INTO collaboration_role_permissions(role_id, permission_id)
SELECT 'ROLE_PROJECT_VIEWER', id FROM collaboration_permissions
WHERE permission_code = 'project.view'
ON CONFLICT DO NOTHING;

COMMIT;
