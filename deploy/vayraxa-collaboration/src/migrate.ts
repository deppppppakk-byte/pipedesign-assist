import fs from "node:fs/promises";
import path from "node:path";
import process from "node:process";
import pg from "pg";

const { Pool } = pg;

const databaseUrl = process.env.VAYRAXA_DATABASE_URL?.trim();
const databaseSsl = process.env.VAYRAXA_DATABASE_SSL !== "0";

if (!databaseUrl) {
  throw new Error("VAYRAXA_DATABASE_URL is required.");
}

const pool = new Pool({
  connectionString: databaseUrl,
  ssl: databaseSsl
    ? { rejectUnauthorized: process.env.VAYRAXA_DATABASE_SSL_VERIFY !== "0" }
    : false,
  max: 2,
});

const migrationDir = path.resolve(process.cwd(), "schema");
const files = (await fs.readdir(migrationDir))
  .filter((name) => /^\d+.*\.sql$/i.test(name))
  .sort((a, b) => a.localeCompare(b));

if (files.length === 0) {
  throw new Error("No collaboration SQL migrations found.");
}

const client = await pool.connect();
try {
  await client.query(
    "CREATE TABLE IF NOT EXISTS collaboration_schema_migrations (" +
      "migration_name TEXT PRIMARY KEY," +
      "applied_at TIMESTAMPTZ NOT NULL DEFAULT now()" +
      ")"
  );

  for (const file of files) {
    const alreadyApplied = await client.query(
      "SELECT 1 FROM collaboration_schema_migrations WHERE migration_name = $1",
      [file],
    );
    if (alreadyApplied.rowCount === 1) {
      console.log(`Skipping already-applied migration: ${file}`);
      continue;
    }

    const sql = await fs.readFile(path.join(migrationDir, file), "utf8");
    await client.query(sql);
    await client.query(
      "INSERT INTO collaboration_schema_migrations (migration_name) VALUES ($1)",
      [file],
    );
    console.log(`Applied migration: ${file}`);
  }
} finally {
  client.release();
  await pool.end();
}
