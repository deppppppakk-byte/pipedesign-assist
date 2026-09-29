import crypto from "node:crypto";
import http from "node:http";
import process from "node:process";
import express, { NextFunction, Request, RequestHandler, Response } from "express";
import helmet from "helmet";
import pg, { PoolClient } from "pg";
import { WebSocket, WebSocketServer } from "ws";
import {
  clampAccessTokenSeconds,
  clampRefreshTokenSeconds,
  fingerprintToken,
  hashPassword,
  randomOpaqueToken,
  randomUuid,
  verifyPassword,
} from "./auth.js";

const { Pool } = pg;

const port = Number.parseInt(
  process.env.VAYRAXA_COLLAB_PORT ??
    process.env.PORT ??
    "8787",
  10,
);
const databaseUrl = process.env.VAYRAXA_DATABASE_URL?.trim();
const allowInsecureLocal = process.env.VAYRAXA_ALLOW_INSECURE_LOCAL === "1";
const databaseSsl = process.env.VAYRAXA_DATABASE_SSL !== "0";
const accessTokenTtlSeconds = clampAccessTokenSeconds(
  Number(process.env.VAYRAXA_ACCESS_TOKEN_TTL_SECONDS ?? "900"),
);
const refreshTokenTtlSeconds = clampRefreshTokenSeconds(
  Number(process.env.VAYRAXA_REFRESH_TOKEN_TTL_SECONDS ?? String(30 * 24 * 60 * 60)),
);
const loginMaxFailures = Math.max(
  3,
  Math.min(Number.parseInt(process.env.VAYRAXA_LOGIN_MAX_FAILURES ?? "5", 10) || 5, 12),
);
const loginLockSeconds = Math.max(
  60,
  Math.min(Number.parseInt(process.env.VAYRAXA_LOGIN_LOCK_SECONDS ?? "900", 10) || 900, 3600),
);

if (!databaseUrl) {
  throw new Error("VAYRAXA_DATABASE_URL is required.");
}

const pool = new Pool({
  connectionString: databaseUrl,
  ssl: databaseSsl ? { rejectUnauthorized: process.env.VAYRAXA_DATABASE_SSL_VERIFY !== "0" } : false,
  max: Number.parseInt(process.env.VAYRAXA_DATABASE_POOL_MAX ?? "20", 10),
});

type AuthContext = {
  userId: string;
  email: string;
  displayName: string;
  tokenId: string;
  clientId: string;
  tokenExpiresAt: Date;
};

type AuthedRequest = Request & { auth?: AuthContext };

type ChangeEvent = {
  projectId: string;
  serverSequence: number;
  transactionId: string;
  userId: string;
  objectType: string;
  objectId: string;
  operation: string;
  baseVersion: number;
  resultingVersion: number;
  payload: unknown;
  createdAt: string;
};

const app = express();
app.set("trust proxy", 1);
app.use(helmet());
app.use(express.json({ limit: "2mb" }));

app.use((req, res, next) => {
  const localHost = req.hostname === "localhost" || req.hostname === "127.0.0.1";
  if (!req.secure && !(allowInsecureLocal && localHost)) {
    res.status(426).json({ error: "HTTPS is required." });
    return;
  }
  next();
});

function sha256(value: string): string {
  return crypto.createHash("sha256").update(value, "utf8").digest("hex");
}

function routeParam(value: string | string[] | undefined): string {
  if (Array.isArray(value)) return value[0] ?? "";
  return String(value ?? "");
}

function bearerToken(header: string | undefined): string | null {
  if (!header) return null;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  return match?.[1]?.trim() || null;
}

async function authenticateAuthorization(header: string | undefined): Promise<AuthContext | null> {
  const token = bearerToken(header);
  if (!token) return null;

  const fingerprint = sha256(token);
  const result = await pool.query(
    `SELECT
       t.id::text AS token_id,
       t.client_id,
       t.expires_at,
       u.id::text AS user_id,
       u.email,
       u.display_name
     FROM collaboration_api_tokens t
     JOIN collaboration_users u ON u.id = t.user_id
     WHERE t.token_fingerprint = $1
       AND t.token_kind IN ('ACCESS','SERVICE')
       AND t.revoked_at IS NULL
       AND t.expires_at > now()
       AND u.status = 'ACTIVE'
     LIMIT 1`,
    [fingerprint],
  );

  const row = result.rows[0];
  if (!row) return null;

  void pool.query(
    "UPDATE collaboration_api_tokens SET last_used_at = now() WHERE id = $1::uuid",
    [row.token_id],
  );

  return {
    userId: row.user_id,
    email: row.email,
    displayName: row.display_name,
    tokenId: row.token_id,
    clientId: row.client_id,
    tokenExpiresAt: new Date(row.expires_at),
  };
}

async function authMiddleware(req: AuthedRequest, res: Response, next: NextFunction) {
  try {
    const auth = await authenticateAuthorization(req.header("authorization"));
    if (!auth) {
      res.status(401).json({ error: "Invalid or expired bearer token." });
      return;
    }
    req.auth = auth;
    next();
  } catch (error) {
    next(error);
  }
}

async function hasPermission(
  client: PoolClient,
  projectId: string,
  userId: string,
  permissionCode: string,
): Promise<boolean> {
  const result = await client.query(
    `SELECT EXISTS(
       SELECT 1
       FROM collaboration_project_members pm
       JOIN collaboration_role_permissions rp ON rp.role_id = pm.role_id
       JOIN collaboration_permissions p ON p.id = rp.permission_id
       WHERE pm.project_id = $1::uuid
         AND pm.user_id = $2::uuid
         AND pm.status = 'ACTIVE'
         AND p.permission_code = $3
     ) AS allowed`,
    [projectId, userId, permissionCode],
  );
  return result.rows[0]?.allowed === true;
}

async function requirePermission(
  res: Response,
  projectId: string,
  userId: string,
  permissionCode: string,
  client?: PoolClient,
): Promise<boolean> {
  const owned = client ?? (await pool.connect());
  try {
    const allowed = await hasPermission(owned, projectId, userId, permissionCode);
    if (!allowed) {
      res.status(403).json({ error: `Missing permission: ${permissionCode}` });
      return false;
    }
    return true;
  } finally {
    if (!client) owned.release();
  }
}

const asyncRoute =
  (handler: (req: AuthedRequest, res: Response) => Promise<void>): RequestHandler =>
  (req: Request, res: Response, next: NextFunction) => {
    void handler(req as AuthedRequest, res).catch(next);
  };

type IssuedTokenPair = {
  accessTokenId: string;
  refreshTokenId: string;
  accessToken: string;
  refreshToken: string;
  accessExpiresAt: Date;
  refreshExpiresAt: Date;
  tokenFamilyId: string;
};

async function issueTokenPair(
  client: PoolClient,
  userId: string,
  clientId: string,
  tokenFamilyId = randomUuid(),
): Promise<IssuedTokenPair> {
  const accessToken = randomOpaqueToken();
  const refreshToken = randomOpaqueToken();
  const accessTokenId = randomUuid();
  const refreshTokenId = randomUuid();
  const accessExpiresAt = new Date(Date.now() + accessTokenTtlSeconds * 1000);
  const refreshExpiresAt = new Date(Date.now() + refreshTokenTtlSeconds * 1000);

  await client.query(
    `INSERT INTO collaboration_api_tokens
       (id, user_id, token_fingerprint, token_kind, client_id, issued_at,
        expires_at, token_family_id)
     VALUES
       ($1::uuid, $2::uuid, $3, 'ACCESS', $4, now(), $5, $6::uuid),
       ($7::uuid, $2::uuid, $8, 'REFRESH', $4, now(), $9, $6::uuid)`,
    [
      accessTokenId,
      userId,
      fingerprintToken(accessToken),
      clientId,
      accessExpiresAt,
      tokenFamilyId,
      refreshTokenId,
      fingerprintToken(refreshToken),
      refreshExpiresAt,
    ],
  );

  return {
    accessTokenId,
    refreshTokenId,
    accessToken,
    refreshToken,
    accessExpiresAt,
    refreshExpiresAt,
    tokenFamilyId,
  };
}

function authResponse(
  pair: IssuedTokenPair,
  user: { id: string; email: string; displayName: string },
) {
  return {
    tokenType: "Bearer",
    accessToken: pair.accessToken,
    accessExpiresAt: pair.accessExpiresAt.toISOString(),
    refreshToken: pair.refreshToken,
    refreshExpiresAt: pair.refreshExpiresAt.toISOString(),
    user,
  };
}

app.get("/health", async (_req, res, next) => {
  try {
    await pool.query("SELECT 1");
    res.json({ ok: true, service: "vayraxa-collaboration", database: "postgresql" });
  } catch (error) {
    next(error);
  }
});

app.post(
  "/v1/auth/login",
  asyncRoute(async (req, res) => {
    const email = String(req.body?.email ?? "").trim().toLowerCase();
    const password = String(req.body?.password ?? "");
    const clientId = String(req.body?.clientId ?? "").trim();

    if (!email || !password || !clientId || password.length > 256) {
      res.status(400).json({ error: "email, password and clientId are required." });
      return;
    }

    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        `SELECT
           u.id::text AS user_id,
           u.email,
           u.display_name,
           u.status,
           c.password_hash,
           c.password_salt,
           c.password_algorithm,
           c.failed_attempts,
           c.locked_until
         FROM collaboration_users u
         JOIN collaboration_user_credentials c ON c.user_id = u.id
         WHERE lower(u.email) = lower($1)
         LIMIT 1
         FOR UPDATE OF u, c`,
        [email],
      );
      const row = result.rows[0];

      if (!row || !row.password_hash || row.status !== "ACTIVE") {
        await client.query("ROLLBACK");
        await hashPassword(password);
        res.status(401).json({ error: "Invalid email or password." });
        return;
      }

      if (row.locked_until && new Date(row.locked_until).getTime() > Date.now()) {
        await client.query("ROLLBACK");
        res.status(429).json({ error: "Sign-in temporarily locked. Try again later." });
        return;
      }

      const valid = await verifyPassword(
        password,
        Buffer.from(row.password_salt),
        Buffer.from(row.password_hash),
        String(row.password_algorithm ?? ""),
      );
      if (!valid) {
        await client.query(
          `UPDATE collaboration_user_credentials
           SET failed_attempts = failed_attempts + 1,
               locked_until = CASE
                 WHEN failed_attempts + 1 >= $2
                   THEN now() + ($3 || ' seconds')::interval
                 ELSE locked_until
               END,
               updated_at = now()
           WHERE user_id = $1::uuid`,
          [row.user_id, loginMaxFailures, loginLockSeconds],
        );
        await client.query("COMMIT");
        res.status(401).json({ error: "Invalid email or password." });
        return;
      }

      await client.query(
        `UPDATE collaboration_user_credentials
         SET failed_attempts = 0, locked_until = NULL, updated_at = now()
         WHERE user_id = $1::uuid`,
        [row.user_id],
      );
      const pair = await issueTokenPair(client, row.user_id, clientId);
      await client.query("COMMIT");
      res.json(
        authResponse(pair, {
          id: row.user_id,
          email: row.email,
          displayName: row.display_name,
        }),
      );
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }),
);

app.post(
  "/v1/auth/refresh",
  asyncRoute(async (req, res) => {
    const refreshToken = String(req.body?.refreshToken ?? "").trim();
    const clientId = String(req.body?.clientId ?? "").trim();
    if (!refreshToken || !clientId) {
      res.status(400).json({ error: "refreshToken and clientId are required." });
      return;
    }

    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      const result = await client.query(
        `SELECT
           t.id::text AS token_id,
           t.user_id::text AS user_id,
           t.client_id,
           t.expires_at,
           t.revoked_at,
           t.token_family_id::text AS token_family_id,
           u.email,
           u.display_name,
           u.status
         FROM collaboration_api_tokens t
         JOIN collaboration_users u ON u.id = t.user_id
         WHERE t.token_fingerprint = $1
           AND t.token_kind = 'REFRESH'
         FOR UPDATE`,
        [fingerprintToken(refreshToken)],
      );
      const row = result.rows[0];

      if (!row || row.client_id !== clientId || row.status !== "ACTIVE") {
        await client.query("ROLLBACK");
        res.status(401).json({ error: "Invalid refresh token." });
        return;
      }

      const familyId = row.token_family_id || randomUuid();
      if (!row.token_family_id) {
        await client.query(
          `UPDATE collaboration_api_tokens
           SET token_family_id = $2::uuid
           WHERE id = $1::uuid`,
          [row.token_id, familyId],
        );
      }
      if (row.revoked_at) {
        await client.query(
          `UPDATE collaboration_api_tokens
           SET revoked_at = COALESCE(revoked_at, now()),
               revoked_reason = COALESCE(revoked_reason, 'REFRESH_REUSE')
           WHERE token_family_id = $1::uuid`,
          [familyId],
        );
        await client.query("COMMIT");
        res.status(401).json({ error: "Refresh token reuse detected; sign in again." });
        return;
      }

      if (new Date(row.expires_at).getTime() <= Date.now()) {
        await client.query(
          `UPDATE collaboration_api_tokens
           SET revoked_at = now(), revoked_reason = 'EXPIRED'
           WHERE id = $1::uuid`,
          [row.token_id],
        );
        await client.query("COMMIT");
        res.status(401).json({ error: "Refresh token expired; sign in again." });
        return;
      }

      const pair = await issueTokenPair(client, row.user_id, clientId, familyId);
      await client.query(
        `UPDATE collaboration_api_tokens
         SET revoked_at = now(),
             revoked_reason = 'ROTATED',
             replaced_by_token_id = $2::uuid,
             last_used_at = now()
         WHERE id = $1::uuid`,
        [row.token_id, pair.refreshTokenId],
      );
      await client.query("COMMIT");

      res.json(
        authResponse(pair, {
          id: row.user_id,
          email: row.email,
          displayName: row.display_name,
        }),
      );
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }),
);

app.post(
  "/v1/auth/logout",
  asyncRoute(async (req, res) => {
    const refreshToken = String(req.body?.refreshToken ?? "").trim();
    if (!refreshToken) {
      res.status(204).end();
      return;
    }

    const result = await pool.query(
      `SELECT token_family_id::text AS token_family_id
       FROM collaboration_api_tokens
       WHERE token_fingerprint = $1
         AND token_kind = 'REFRESH'
       LIMIT 1`,
      [fingerprintToken(refreshToken)],
    );
    const familyId = result.rows[0]?.token_family_id;
    if (familyId) {
      await pool.query(
        `UPDATE collaboration_api_tokens
         SET revoked_at = COALESCE(revoked_at, now()),
             revoked_reason = COALESCE(revoked_reason, 'LOGOUT')
         WHERE token_family_id = $1::uuid`,
        [familyId],
      );
    } else {
      await pool.query(
        `UPDATE collaboration_api_tokens
         SET revoked_at = COALESCE(revoked_at, now()),
             revoked_reason = COALESCE(revoked_reason, 'LOGOUT')
         WHERE token_fingerprint = $1 AND token_kind = 'REFRESH'`,
        [fingerprintToken(refreshToken)],
      );
    }
    res.status(204).end();
  }),
);

app.use("/v1", authMiddleware);

app.post(
  "/v1/sessions",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const requestedSeconds = Number(req.body?.expiresInSeconds ?? 43200);
    const expiresSeconds = Math.max(300, Math.min(requestedSeconds, 43200));
    const requestedExpiry = new Date(Date.now() + expiresSeconds * 1000);
    const expiresAt =
      requestedExpiry < auth.tokenExpiresAt ? requestedExpiry : auth.tokenExpiresAt;
    const sessionId = crypto.randomUUID();

    await pool.query(
      `INSERT INTO collaboration_user_sessions
       (id, user_id, client_id, device_name, client_version, status, expires_at)
       VALUES ($1::uuid, $2::uuid, $3, $4, $5, 'ACTIVE', $6)`,
      [
        sessionId,
        auth.userId,
        String(req.body?.clientId ?? auth.clientId),
        String(req.body?.deviceName ?? ""),
        String(req.body?.clientVersion ?? ""),
        expiresAt,
      ],
    );

    res.status(201).json({ sessionId, expiresAt: expiresAt.toISOString() });
  }),
);

app.post(
  "/v1/sessions/:sessionId/heartbeat",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const result = await pool.query(
      `UPDATE collaboration_user_sessions
       SET last_seen_at = now()
       WHERE id = $1::uuid
         AND user_id = $2::uuid
         AND status = 'ACTIVE'
         AND expires_at > now()
       RETURNING expires_at`,
      [routeParam(req.params.sessionId), auth.userId],
    );
    if (result.rowCount !== 1) {
      res.status(404).json({ error: "Active session not found." });
      return;
    }
    res.json({ ok: true, expiresAt: result.rows[0].expires_at });
  }),
);

app.put(
  "/v1/projects/:projectId/presence",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "project.view"))) return;

    const sessionId = String(req.body?.sessionId ?? "");
    const state = String(req.body?.presenceState ?? "VIEWING").toUpperCase();
    if (!["VIEWING", "EDITING", "AWAY", "DISCONNECTED"].includes(state)) {
      res.status(400).json({ error: "Invalid presence state." });
      return;
    }

    const session = await pool.query(
      `SELECT 1 FROM collaboration_user_sessions
       WHERE id = $1::uuid AND user_id = $2::uuid
         AND status = 'ACTIVE' AND expires_at > now()`,
      [sessionId, auth.userId],
    );
    if (session.rowCount !== 1) {
      res.status(409).json({ error: "Session is not active for this user." });
      return;
    }

    await pool.query(
      `INSERT INTO collaboration_project_presence
       (project_id, user_id, session_id, presence_state, opened_at, last_heartbeat_at, closed_at)
       VALUES ($1::uuid, $2::uuid, $3::uuid, $4, now(), now(), NULL)
       ON CONFLICT(project_id, session_id) DO UPDATE SET
         user_id = EXCLUDED.user_id,
         presence_state = EXCLUDED.presence_state,
         last_heartbeat_at = now(),
         closed_at = CASE WHEN EXCLUDED.presence_state = 'DISCONNECTED' THEN now() ELSE NULL END`,
      [projectId, auth.userId, sessionId, state],
    );
    res.json({ ok: true });
  }),
);

app.post(
  "/v1/projects/:projectId/leases/acquire",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    const objectType = String(req.body?.objectType ?? "").trim();
    const objectId = String(req.body?.objectId ?? "").trim();
    const sessionId = String(req.body?.sessionId ?? "").trim();
    const leaseScope = String(req.body?.leaseScope ?? "EDIT").toUpperCase();
    const requestedSeconds = Number(req.body?.expiresInSeconds ?? 120);
    const leaseSeconds = Math.max(30, Math.min(requestedSeconds, 600));

    if (!objectType || !objectId || !sessionId || !["EDIT", "REVIEW", "ADMIN"].includes(leaseScope)) {
      res.status(400).json({ error: "objectType, objectId, sessionId and valid leaseScope are required." });
      return;
    }

    const client = await pool.connect();
    try {
      await client.query("BEGIN");
      if (!(await requirePermission(res, projectId, auth.userId, "lock.acquire", client))) {
        await client.query("ROLLBACK");
        return;
      }

      const validSession = await client.query(
        `SELECT 1 FROM collaboration_user_sessions
         WHERE id = $1::uuid AND user_id = $2::uuid
           AND status = 'ACTIVE' AND expires_at > now()`,
        [sessionId, auth.userId],
      );
      if (validSession.rowCount !== 1) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "Session is not active for this user." });
        return;
      }

      await client.query(
        `UPDATE collaboration_object_leases SET status = 'EXPIRED'
         WHERE project_id = $1::uuid AND object_type = $2 AND object_id = $3
           AND status = 'ACTIVE' AND expires_at <= now()`,
        [projectId, objectType, objectId],
      );

      const existing = await client.query(
        `SELECT id::text, user_id::text, session_id::text, lease_token::text, lease_version
         FROM collaboration_object_leases
         WHERE project_id = $1::uuid AND object_type = $2 AND object_id = $3
           AND status = 'ACTIVE' AND expires_at > now()
         FOR UPDATE`,
        [projectId, objectType, objectId],
      );

      const row = existing.rows[0];
      if (row && row.session_id !== sessionId) {
        await client.query("ROLLBACK");
        res.status(409).json({ error: "Object is leased by another active session." });
        return;
      }

      const expiresAt = new Date(Date.now() + leaseSeconds * 1000);
      if (row) {
        const renewed = await client.query(
          `UPDATE collaboration_object_leases
           SET heartbeat_at = now(), expires_at = $1, lease_version = lease_version + 1
           WHERE id = $2::uuid
           RETURNING id::text, lease_token::text, lease_version, expires_at`,
          [expiresAt, row.id],
        );
        await client.query("COMMIT");
        res.json(renewed.rows[0]);
        return;
      }

      const leaseId = crypto.randomUUID();
      const leaseToken = crypto.randomUUID();
      const inserted = await client.query(
        `INSERT INTO collaboration_object_leases
         (id, project_id, object_type, object_id, user_id, session_id, lease_scope,
          lease_token, status, expires_at)
         VALUES ($1::uuid, $2::uuid, $3, $4, $5::uuid, $6::uuid, $7, $8::uuid, 'ACTIVE', $9)
         RETURNING id::text, lease_token::text, lease_version, expires_at`,
        [leaseId, projectId, objectType, objectId, auth.userId, sessionId, leaseScope, leaseToken, expiresAt],
      );
      await client.query("COMMIT");
      res.status(201).json(inserted.rows[0]);
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }),
);

app.post(
  "/v1/projects/:projectId/leases/:leaseId/renew",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    const leaseToken = String(req.body?.leaseToken ?? "");
    const seconds = Math.max(30, Math.min(Number(req.body?.expiresInSeconds ?? 120), 600));
    const result = await pool.query(
      `UPDATE collaboration_object_leases
       SET heartbeat_at = now(), expires_at = now() + ($1 || ' seconds')::interval,
           lease_version = lease_version + 1
       WHERE id = $2::uuid AND project_id = $3::uuid AND user_id = $4::uuid
         AND lease_token::text = $5 AND status = 'ACTIVE' AND expires_at > now()
       RETURNING id::text, lease_token::text, lease_version, expires_at`,
      [seconds, routeParam(req.params.leaseId), projectId, auth.userId, leaseToken],
    );
    if (result.rowCount !== 1) {
      res.status(409).json({ error: "Active lease not found or token mismatch." });
      return;
    }
    res.json(result.rows[0]);
  }),
);

app.post(
  "/v1/projects/:projectId/leases/:leaseId/release",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const result = await pool.query(
      `UPDATE collaboration_object_leases
       SET status = 'RELEASED', heartbeat_at = now()
       WHERE id = $1::uuid AND project_id = $2::uuid AND user_id = $3::uuid
         AND lease_token::text = $4 AND status = 'ACTIVE'
       RETURNING id::text`,
      [routeParam(req.params.leaseId), req.params.projectId, auth.userId, String(req.body?.leaseToken ?? "")],
    );
    if (result.rowCount !== 1) {
      res.status(409).json({ error: "Active lease not found or token mismatch." });
      return;
    }
    res.json({ ok: true });
  }),
);

const projectSockets = new Map<string, Set<WebSocket>>();

function broadcast(event: ChangeEvent) {
  const payload = JSON.stringify({ type: "project.change", event });
  for (const socket of projectSockets.get(event.projectId) ?? []) {
    if (socket.readyState === WebSocket.OPEN) socket.send(payload);
  }
}

app.post(
  "/v1/projects/:projectId/transactions",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    const clientTransactionId = String(req.body?.clientTransactionId ?? "").trim();
    const objectType = String(req.body?.objectType ?? "").trim();
    const objectId = String(req.body?.objectId ?? "").trim();
    const operation = String(req.body?.operation ?? "").trim().toUpperCase();
    const leaseToken = String(req.body?.leaseToken ?? "").trim();
    const baseVersion = Number(req.body?.baseVersion ?? -1);
    const payload = req.body?.payload ?? {};

    if (
      !clientTransactionId ||
      !objectType ||
      !objectId ||
      !["CREATE", "UPDATE", "DELETE", "ATTACH_BLOB", "DETACH_BLOB"].includes(operation) ||
      !Number.isSafeInteger(baseVersion) ||
      baseVersion < 0
    ) {
      res.status(400).json({ error: "Invalid transaction envelope." });
      return;
    }

    const permission =
      operation === "CREATE"
        ? "object.create"
        : operation === "DELETE"
          ? "object.delete"
          : "object.edit";

    const client = await pool.connect();
    let emitted: ChangeEvent | null = null;
    try {
      await client.query("BEGIN");

      const duplicate = await client.query(
        `SELECT
           t.id::text AS transaction_id,
           t.resulting_version,
           e.server_sequence
         FROM collaboration_transactions t
         JOIN collaboration_change_events e ON e.transaction_id = t.id
         WHERE t.project_id = $1::uuid AND t.client_transaction_id = $2
         LIMIT 1`,
        [projectId, clientTransactionId],
      );
      if (duplicate.rowCount === 1) {
        await client.query("COMMIT");
        res.json({ ...duplicate.rows[0], idempotentReplay: true });
        return;
      }

      if (!(await requirePermission(res, projectId, auth.userId, permission, client))) {
        await client.query("ROLLBACK");
        return;
      }

      const versionResult = await client.query(
        `SELECT object_version
         FROM collaboration_object_versions
         WHERE project_id = $1::uuid AND object_type = $2 AND object_id = $3
         FOR UPDATE`,
        [projectId, objectType, objectId],
      );
      const currentVersion = Number(versionResult.rows[0]?.object_version ?? 0);
      if (currentVersion !== baseVersion) {
        const latestEvent = await client.query(
          `SELECT server_sequence AS "serverSequence",
                  transaction_id::text AS "transactionId",
                  operation,
                  resulting_version AS "resultingVersion",
                  payload,
                  created_at AS "createdAt"
           FROM collaboration_change_events
           WHERE project_id = $1::uuid
             AND object_type = $2
             AND object_id = $3
           ORDER BY server_sequence DESC
           LIMIT 1`,
          [projectId, objectType, objectId],
        );
        await client.query("ROLLBACK");
        const latest = latestEvent.rows[0] ?? null;
        res.status(409).json({
          error: "Stale object version.",
          objectType,
          objectId,
          expectedVersion: baseVersion,
          serverVersion: currentVersion,
          serverSequence: latest ? Number(latest.serverSequence) : null,
          serverTransactionId: latest?.transactionId ?? null,
          serverOperation: latest?.operation ?? null,
          serverPayload: latest?.payload ?? null,
          serverCreatedAt: latest ? new Date(latest.createdAt).toISOString() : null,
        });
        return;
      }

      if (operation === "UPDATE" || operation === "DELETE") {
        if (!leaseToken) {
          await client.query("ROLLBACK");
          res.status(409).json({ error: "An active edit lease token is required." });
          return;
        }
        const lease = await client.query(
          `SELECT 1
           FROM collaboration_object_leases
           WHERE project_id = $1::uuid AND object_type = $2 AND object_id = $3
             AND user_id = $4::uuid AND lease_token::text = $5
             AND status = 'ACTIVE' AND expires_at > now()
           LIMIT 1`,
          [projectId, objectType, objectId, auth.userId, leaseToken],
        );
        if (lease.rowCount !== 1) {
          await client.query("ROLLBACK");
          res.status(409).json({ error: "Edit lease is missing, expired, or owned by another session." });
          return;
        }
      }

      const transactionId = crypto.randomUUID();
      const resultingVersion = currentVersion + 1;

      await client.query(
        `INSERT INTO collaboration_object_versions
         (project_id, object_type, object_id, object_version, last_transaction_id, modified_by, modified_at)
         VALUES ($1::uuid, $2, $3, $4, $5::uuid, $6::uuid, now())
         ON CONFLICT(project_id, object_type, object_id) DO UPDATE SET
           object_version = EXCLUDED.object_version,
           last_transaction_id = EXCLUDED.last_transaction_id,
           modified_by = EXCLUDED.modified_by,
           modified_at = now()`,
        [projectId, objectType, objectId, resultingVersion, transactionId, auth.userId],
      );

      await client.query(
        `INSERT INTO collaboration_project_sequences(project_id, last_sequence)
         VALUES ($1::uuid, 0)
         ON CONFLICT(project_id) DO NOTHING`,
        [projectId],
      );
      const sequenceResult = await client.query(
        `UPDATE collaboration_project_sequences
         SET last_sequence = last_sequence + 1
         WHERE project_id = $1::uuid
         RETURNING last_sequence`,
        [projectId],
      );
      const serverSequence = Number(sequenceResult.rows[0].last_sequence);

      await client.query(
        `INSERT INTO collaboration_transactions
         (id, project_id, client_transaction_id, user_id, object_type, object_id,
          operation, base_version, resulting_version, payload)
         VALUES ($1::uuid, $2::uuid, $3, $4::uuid, $5, $6, $7, $8, $9, $10::jsonb)`,
        [
          transactionId,
          projectId,
          clientTransactionId,
          auth.userId,
          objectType,
          objectId,
          operation,
          baseVersion,
          resultingVersion,
          JSON.stringify(payload),
        ],
      );

      const eventInsert = await client.query(
        `INSERT INTO collaboration_change_events
         (project_id, server_sequence, transaction_id, user_id, object_type, object_id,
          operation, base_version, resulting_version, payload)
         VALUES ($1::uuid, $2, $3::uuid, $4::uuid, $5, $6, $7, $8, $9, $10::jsonb)
         RETURNING created_at`,
        [
          projectId,
          serverSequence,
          transactionId,
          auth.userId,
          objectType,
          objectId,
          operation,
          baseVersion,
          resultingVersion,
          JSON.stringify(payload),
        ],
      );

      await client.query("COMMIT");

      emitted = {
        projectId,
        serverSequence,
        transactionId,
        userId: auth.userId,
        objectType,
        objectId,
        operation,
        baseVersion,
        resultingVersion,
        payload,
        createdAt: new Date(eventInsert.rows[0].created_at).toISOString(),
      };

      res.status(201).json(emitted);
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }

    if (emitted) broadcast(emitted);
  }),
);

app.get(
  "/v1/projects/:projectId/events",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "project.view"))) return;

    const after = Math.max(0, Number.parseInt(String(req.query.after ?? "0"), 10) || 0);
    const limit = Math.max(1, Math.min(Number.parseInt(String(req.query.limit ?? "200"), 10) || 200, 1000));

    const result = await pool.query(
      `SELECT
         project_id::text AS "projectId",
         server_sequence AS "serverSequence",
         transaction_id::text AS "transactionId",
         user_id::text AS "userId",
         object_type AS "objectType",
         object_id AS "objectId",
         operation,
         base_version AS "baseVersion",
         resulting_version AS "resultingVersion",
         payload,
         created_at AS "createdAt"
       FROM collaboration_change_events
       WHERE project_id = $1::uuid AND server_sequence > $2
       ORDER BY server_sequence
       LIMIT $3`,
      [projectId, after, limit],
    );

    const events = result.rows.map((row) => ({
      ...row,
      serverSequence: Number(row.serverSequence),
      baseVersion: Number(row.baseVersion),
      resultingVersion: Number(row.resultingVersion),
      createdAt: new Date(row.createdAt).toISOString(),
    }));
    const cursor = events.length > 0 ? events[events.length - 1]!.serverSequence : after;
    res.json({ projectId, after, cursor, events });
  }),
);

app.put(
  "/v1/projects/:projectId/blobs/:blobId",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "object.edit"))) return;

    const objectKey = String(req.body?.objectKey ?? "").trim();
    const sha = String(req.body?.sha256 ?? "").trim().toLowerCase();
    const sizeBytes = Number(req.body?.sizeBytes ?? -1);
    if (!objectKey || !/^[a-f0-9]{64}$/.test(sha) || !Number.isSafeInteger(sizeBytes) || sizeBytes < 0) {
      res.status(400).json({ error: "Valid objectKey, sha256 and sizeBytes are required." });
      return;
    }

    const result = await pool.query(
      `INSERT INTO collaboration_blob_refs
       (blob_id, project_id, object_type, object_id, object_key, sha256, size_bytes,
        media_type, storage_url, created_by)
       VALUES ($1::uuid, $2::uuid, $3, $4, $5, $6, $7, $8, $9, $10::uuid)
       ON CONFLICT(blob_id) DO UPDATE SET
         object_type = EXCLUDED.object_type,
         object_id = EXCLUDED.object_id,
         object_key = EXCLUDED.object_key,
         sha256 = EXCLUDED.sha256,
         size_bytes = EXCLUDED.size_bytes,
         media_type = EXCLUDED.media_type,
         storage_url = EXCLUDED.storage_url,
         updated_at = now()
       RETURNING blob_id::text AS "blobId", object_key AS "objectKey", sha256,
                 size_bytes AS "sizeBytes", storage_url AS "storageUrl", updated_at AS "updatedAt"`,
      [
        routeParam(req.params.blobId),
        projectId,
        String(req.body?.objectType ?? "") || null,
        String(req.body?.objectId ?? "") || null,
        objectKey,
        sha,
        sizeBytes,
        String(req.body?.mediaType ?? "") || null,
        String(req.body?.remoteUrl ?? "") || null,
        auth.userId,
      ],
    );
    res.json(result.rows[0]);
  }),
);

app.get(
  "/v1/projects/:projectId/state",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "project.view"))) return;

    const [presence, leases] = await Promise.all([
      pool.query(
        `SELECT p.user_id::text AS "userId", u.display_name AS "displayName",
                p.session_id::text AS "sessionId", p.presence_state AS "presenceState",
                p.last_heartbeat_at AS "lastHeartbeatAt"
         FROM collaboration_project_presence p
         JOIN collaboration_users u ON u.id = p.user_id
         WHERE p.project_id = $1::uuid
           AND p.presence_state <> 'DISCONNECTED'
           AND p.last_heartbeat_at > now() - interval '5 minutes'
         ORDER BY p.last_heartbeat_at DESC`,
        [projectId],
      ),
      pool.query(
        `SELECT l.id::text AS "leaseId", l.object_type AS "objectType",
                l.object_id AS "objectId", l.user_id::text AS "userId",
                u.display_name AS "displayName", l.session_id::text AS "sessionId",
                l.lease_scope AS "leaseScope", l.status,
                l.acquired_at AS "acquiredAt", l.expires_at AS "expiresAt",
                l.lease_version AS "leaseVersion"
         FROM collaboration_object_leases l
         JOIN collaboration_users u ON u.id = l.user_id
         WHERE l.project_id = $1::uuid
           AND l.status = 'ACTIVE'
           AND l.expires_at > now()
         ORDER BY l.acquired_at`,
        [projectId],
      ),
    ]);

    res.json({
      projectId,
      presence: presence.rows,
      leases: leases.rows,
      serverTime: new Date().toISOString(),
    });
  }),
);

app.get(
  "/v1/projects/:projectId/admin/snapshot",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "project.manage_members"))) return;

    const [members, sessions, leases, presence] = await Promise.all([
      pool.query(
        `SELECT pm.user_id::text AS "userId", u.email, u.display_name AS "displayName",
                pm.role_id AS "roleId", r.role_name AS "roleName",
                pm.discipline_code AS "disciplineCode", pm.status
         FROM collaboration_project_members pm
         JOIN collaboration_users u ON u.id = pm.user_id
         JOIN collaboration_roles r ON r.id = pm.role_id
         WHERE pm.project_id = $1::uuid
         ORDER BY u.display_name`,
        [projectId],
      ),
      pool.query(
        `SELECT s.id::text AS "sessionId", s.user_id::text AS "userId", u.display_name AS "displayName",
                s.client_id AS "clientId", s.device_name AS "deviceName",
                s.client_version AS "clientVersion", s.status,
                s.last_seen_at AS "lastSeenAt", s.expires_at AS "expiresAt"
         FROM collaboration_user_sessions s
         JOIN collaboration_users u ON u.id = s.user_id
         JOIN collaboration_project_members pm ON pm.user_id = s.user_id
         WHERE pm.project_id = $1::uuid
         ORDER BY s.last_seen_at DESC`,
        [projectId],
      ),
      pool.query(
        `SELECT l.id::text AS "leaseId", l.object_type AS "objectType", l.object_id AS "objectId",
                l.user_id::text AS "userId", u.display_name AS "displayName",
                l.session_id::text AS "sessionId", l.lease_scope AS "leaseScope",
                l.status, l.acquired_at AS "acquiredAt", l.expires_at AS "expiresAt",
                l.lease_version AS "leaseVersion"
         FROM collaboration_object_leases l
         JOIN collaboration_users u ON u.id = l.user_id
         WHERE l.project_id = $1::uuid AND l.status = 'ACTIVE' AND l.expires_at > now()
         ORDER BY l.acquired_at`,
        [projectId],
      ),
      pool.query(
        `SELECT p.user_id::text AS "userId", u.display_name AS "displayName",
                p.session_id::text AS "sessionId", p.presence_state AS "presenceState",
                p.last_heartbeat_at AS "lastHeartbeatAt"
         FROM collaboration_project_presence p
         JOIN collaboration_users u ON u.id = p.user_id
         WHERE p.project_id = $1::uuid AND p.presence_state <> 'DISCONNECTED'
         ORDER BY p.last_heartbeat_at DESC`,
        [projectId],
      ),
    ]);

    res.json({
      projectId,
      members: members.rows,
      sessions: sessions.rows,
      leases: leases.rows,
      presence: presence.rows,
    });
  }),
);

app.put(
  "/v1/projects/:projectId/members/:userId/role",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "project.manage_members"))) return;

    const roleId = String(req.body?.roleId ?? "").trim();
    const result = await pool.query(
      `UPDATE collaboration_project_members pm
       SET role_id = $1, updated_at = now()
       FROM collaboration_roles r
       WHERE pm.project_id = $2::uuid AND pm.user_id = $3::uuid
         AND r.id = $1
       RETURNING pm.user_id::text AS "userId", pm.role_id AS "roleId"`,
      [roleId, projectId, routeParam(req.params.userId)],
    );
    if (result.rowCount !== 1) {
      res.status(404).json({ error: "Project member or role not found." });
      return;
    }
    res.json(result.rows[0]);
  }),
);

app.post(
  "/v1/projects/:projectId/leases/:leaseId/force-release",
  asyncRoute(async (req, res) => {
    const auth = req.auth!;
    const projectId = routeParam(req.params.projectId);
    if (!(await requirePermission(res, projectId, auth.userId, "lock.force_release"))) return;

    const result = await pool.query(
      `UPDATE collaboration_object_leases
       SET status = 'FORCED', heartbeat_at = now()
       WHERE id = $1::uuid AND project_id = $2::uuid AND status = 'ACTIVE'
       RETURNING id::text AS "leaseId"`,
      [routeParam(req.params.leaseId), projectId],
    );
    if (result.rowCount !== 1) {
      res.status(404).json({ error: "Active lease not found." });
      return;
    }
    res.json({ ok: true, ...result.rows[0] });
  }),
);

app.use((error: unknown, _req: Request, res: Response, _next: NextFunction) => {
  console.error(error);
  const message = error instanceof Error ? error.message : "Internal server error.";
  res.status(500).json({ error: message });
});

const server = http.createServer(app);
const websocketServer = new WebSocketServer({ noServer: true });

server.on("upgrade", (request, socket, head) => {
  void (async () => {
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      const match = /^\/v1\/projects\/([^/]+)\/stream$/.exec(url.pathname);
      if (!match) {
        socket.destroy();
        return;
      }

      const projectId = match[1]!;
      const auth = await authenticateAuthorization(request.headers.authorization);
      if (!auth) {
        socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
        socket.destroy();
        return;
      }

      const client = await pool.connect();
      try {
        if (!(await hasPermission(client, projectId, auth.userId, "project.view"))) {
          socket.write("HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n");
          socket.destroy();
          return;
        }
      } finally {
        client.release();
      }

      websocketServer.handleUpgrade(request, socket, head, (ws) => {
        const sockets = projectSockets.get(projectId) ?? new Set<WebSocket>();
        sockets.add(ws);
        projectSockets.set(projectId, sockets);

        ws.send(
          JSON.stringify({
            type: "stream.ready",
            projectId,
            userId: auth.userId,
            at: new Date().toISOString(),
          }),
        );

        ws.on("close", () => {
          sockets.delete(ws);
          if (sockets.size === 0) projectSockets.delete(projectId);
        });
        ws.on("error", () => {
          sockets.delete(ws);
        });
      });
    } catch {
      socket.destroy();
    }
  })();
});

const heartbeat = setInterval(() => {
  for (const sockets of projectSockets.values()) {
    for (const socket of sockets) {
      if (socket.readyState === WebSocket.OPEN) socket.ping();
    }
  }
}, 30000);
heartbeat.unref();

server.listen(port, "0.0.0.0", () => {
  console.log(`VAYRAXA collaboration service listening on :${port}`);
});

async function shutdown(signal: string) {
  console.log(`Received ${signal}; shutting down collaboration service.`);
  clearInterval(heartbeat);
  for (const sockets of projectSockets.values()) {
    for (const socket of sockets) socket.close(1001, "server shutdown");
  }
  await new Promise<void>((resolve) => server.close(() => resolve()));
  await pool.end();
  process.exit(0);
}

process.on("SIGTERM", () => void shutdown("SIGTERM"));
process.on("SIGINT", () => void shutdown("SIGINT"));
