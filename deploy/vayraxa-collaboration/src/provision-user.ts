import process from "node:process";
import pg from "pg";
import {
  hashPassword,
  randomUuid,
  validPasswordPolicy,
} from "./auth.js";

const { Pool } = pg;

const databaseUrl = process.env.VAYRAXA_DATABASE_URL?.trim();
const email = process.env.VAYRAXA_PROVISION_EMAIL?.trim().toLowerCase();
const password = process.env.VAYRAXA_PROVISION_PASSWORD ?? "";
const displayName = process.env.VAYRAXA_PROVISION_DISPLAY_NAME?.trim() || email || "";
const projectId = process.env.VAYRAXA_PROVISION_PROJECT_ID?.trim();
const roleId = process.env.VAYRAXA_PROVISION_ROLE_ID?.trim() || "ROLE_PROJECT_OWNER";
const databaseSsl = process.env.VAYRAXA_DATABASE_SSL !== "0";

if (!databaseUrl) throw new Error("VAYRAXA_DATABASE_URL is required.");
if (!email || !email.includes("@")) throw new Error("VAYRAXA_PROVISION_EMAIL is required.");
if (!validPasswordPolicy(password)) {
  throw new Error("VAYRAXA_PROVISION_PASSWORD must be 12-256 characters and contain letters and numbers.");
}

const pool = new Pool({
  connectionString: databaseUrl,
  ssl: databaseSsl
    ? { rejectUnauthorized: process.env.VAYRAXA_DATABASE_SSL_VERIFY !== "0" }
    : false,
  max: 2,
});

const client = await pool.connect();
try {
  await client.query("BEGIN");

  const existing = await client.query(
    `SELECT id::text FROM collaboration_users WHERE lower(email) = lower($1) LIMIT 1`,
    [email],
  );
  const userId = existing.rows[0]?.id ?? randomUuid();

  if (existing.rowCount === 1) {
    await client.query(
      `UPDATE collaboration_users
       SET email = $2,
           display_name = $3,
           auth_subject = COALESCE(auth_subject, $4),
           status = 'ACTIVE',
           updated_at = now()
       WHERE id = $1::uuid`,
      [userId, email, displayName, `local:${email}`],
    );
  } else {
    await client.query(
      `INSERT INTO collaboration_users
         (id, email, display_name, auth_subject, status)
       VALUES ($1::uuid, $2, $3, $4, 'ACTIVE')`,
      [userId, email, displayName, `local:${email}`],
    );
  }

  const material = await hashPassword(password);
  await client.query(
    `INSERT INTO collaboration_user_credentials
       (user_id, password_hash, password_salt, password_algorithm,
        failed_attempts, locked_until, password_changed_at, updated_at)
     VALUES ($1::uuid, $2, $3, $4, 0, NULL, now(), now())
     ON CONFLICT(user_id) DO UPDATE SET
       password_hash = EXCLUDED.password_hash,
       password_salt = EXCLUDED.password_salt,
       password_algorithm = EXCLUDED.password_algorithm,
       failed_attempts = 0,
       locked_until = NULL,
       password_changed_at = now(),
       updated_at = now()`,
    [userId, material.hash, material.salt, material.algorithm],
  );

  await client.query(
    `UPDATE collaboration_api_tokens
     SET revoked_at = COALESCE(revoked_at, now()),
         revoked_reason = COALESCE(revoked_reason, 'PASSWORD_RESET')
     WHERE user_id = $1::uuid`,
    [userId],
  );

  if (projectId) {
    const role = await client.query(
      `SELECT id FROM collaboration_roles WHERE id = $1 LIMIT 1`,
      [roleId],
    );
    if (role.rowCount !== 1) {
      throw new Error(`Role not found: ${roleId}`);
    }
    await client.query(
      `INSERT INTO collaboration_project_members
         (project_id, user_id, role_id, status)
       VALUES ($1::uuid, $2::uuid, $3, 'ACTIVE')
       ON CONFLICT(project_id, user_id) DO UPDATE SET
         role_id = EXCLUDED.role_id,
         status = 'ACTIVE',
         updated_at = now()`,
      [projectId, userId, roleId],
    );
  }

  await client.query("COMMIT");
  console.log(
    JSON.stringify(
      {
        ok: true,
        userId,
        email,
        displayName,
        projectId: projectId || null,
        roleId: projectId ? roleId : null,
      },
      null,
      2,
    ),
  );
} catch (error) {
  await client.query("ROLLBACK");
  throw error;
} finally {
  client.release();
  await pool.end();
}
