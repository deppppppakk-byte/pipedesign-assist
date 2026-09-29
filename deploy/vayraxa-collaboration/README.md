# VAYRAXA Collaboration Service — R56

This service is the central multi-user authority for VAYRAXA desktops.

## Runtime topology

```text
Desktop PC 1 ----\
Desktop PC 2 ----- HTTPS + WSS ---> collaboration service ---> PostgreSQL
Desktop PC N ----/                         |
                                           +---- object/blob storage
```

SQLite remains local to each workstation. Do not put a project SQLite database
on SMB, OneDrive, Google Drive, Dropbox, or another shared/synchronized folder.

## Implemented endpoints

- `GET /health`
- `POST /v1/auth/login`
- `POST /v1/auth/refresh`
- `POST /v1/auth/logout`
- `POST /v1/sessions`
- `POST /v1/sessions/:sessionId/heartbeat`
- `PUT /v1/projects/:projectId/presence`
- `POST /v1/projects/:projectId/leases/acquire`
- `POST /v1/projects/:projectId/leases/:leaseId/renew`
- `POST /v1/projects/:projectId/leases/:leaseId/release`
- `POST /v1/projects/:projectId/transactions`
- `GET /v1/projects/:projectId/events?after=<sequence>&limit=<n>`
- `WS /v1/projects/:projectId/stream`
- `PUT /v1/projects/:projectId/blobs/:blobId`
- `GET /v1/projects/:projectId/state`
- `GET /v1/projects/:projectId/admin/snapshot`
- `PUT /v1/projects/:projectId/members/:userId/role`
- `POST /v1/projects/:projectId/leases/:leaseId/force-release`

## Authority rules

The service rejects edits unless all applicable checks succeed:

1. bearer access token is active and unexpired;
2. the user is an active project member;
3. RBAC grants the operation;
4. UPDATE/DELETE owns an unexpired object edit lease;
5. the submitted base object version exactly matches PostgreSQL;
6. the client transaction ID is idempotent within the project.

PostgreSQL assigns the authoritative transaction ID, resulting object version,
and monotonic per-project event sequence.

## PostgreSQL

Apply both authority migrations in order:

```bash
psql "$VAYRAXA_DATABASE_URL" -f schema/001_collaboration_authority.sql
psql "$VAYRAXA_DATABASE_URL" -f schema/002_auth_refresh_credentials.sql
```

The schema contains identity/RBAC projection, password credentials, token
fingerprints/families, sessions, presence, leases, object versions,
transaction journal, delta events and blob metadata.

Passwords are hashed with scrypt and a per-user random salt. Raw access and
refresh tokens are **not** stored in PostgreSQL; only SHA-256 token
fingerprints are retained. Access tokens are short lived. Refresh tokens are
rotated on every refresh, and reuse of an already-rotated refresh token
revokes its token family.

## Provision the first user

Provisioning reads the password from the process environment and never writes
it into the repository:

```bash
VAYRAXA_DATABASE_URL=postgresql://... \
VAYRAXA_PROVISION_EMAIL=owner@example.com \
VAYRAXA_PROVISION_DISPLAY_NAME="Project Owner" \
VAYRAXA_PROVISION_PASSWORD="replace-with-a-strong-password" \
VAYRAXA_PROVISION_PROJECT_ID="<project-uuid>" \
VAYRAXA_PROVISION_ROLE_ID=ROLE_PROJECT_OWNER \
npm run provision-user
```

Changing a provisioned password revokes the user's existing token families.

## HTTPS and WebSockets

Production traffic must be terminated by a TLS reverse proxy. The service
requires HTTPS unless `VAYRAXA_ALLOW_INSECURE_LOCAL=1` and the request is
localhost.

The reverse proxy must preserve:

- `Authorization`
- `X-VAYRAXA-Client`
- `X-Forwarded-Proto: https`
- WebSocket `Upgrade` / `Connection` headers

WebSocket authentication uses the Authorization header during the HTTP upgrade;
tokens are not placed in the WebSocket URL.

## Native desktop authentication

The shipped Windows PlantCAD client signs in with email/password over HTTPS.
The password is used only for the login request and is not persisted.

- access token: memory only;
- refresh token: Windows Credential Manager;
- token refresh: scheduled before access-token expiry and retried on HTTP 401;
- logout: server token-family revocation + local credential deletion.

The production native client does not require an access token in an
environment variable.

## Run locally

```bash
npm install
npm run build
VAYRAXA_DATABASE_URL=postgresql://... \
VAYRAXA_DATABASE_SSL=0 \
VAYRAXA_ALLOW_INSECURE_LOCAL=1 \
npm start
```

## Tamishra deployment

The proposed public service address is `collab.tamishra.in`. This is a
deployment target, not a claim that DNS is already configured.

Use `deploy/tamishra/vayraxa-collaboration.env.example` as the environment
contract. Keep real values in the hosting platform's secret manager.

A persistent container/VM service is required because the project event stream
uses long-lived WebSocket connections. A purely serverless request runtime is
not the correct host for this service.
