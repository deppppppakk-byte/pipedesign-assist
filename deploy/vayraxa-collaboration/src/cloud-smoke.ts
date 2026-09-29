import process from "node:process";
import WebSocket from "ws";

const apiBase = (process.env.VAYRAXA_COLLAB_API_URL ?? "").trim().replace(/\/$/, "");
const clientId = (process.env.VAYRAXA_COLLAB_CLIENT_ID ?? "vayraxa-cloud-smoke").trim();
const projectId = (process.env.VAYRAXA_COLLAB_PROJECT_ID ?? "").trim();
const email = (process.env.VAYRAXA_SMOKE_EMAIL ?? "").trim();
const password = process.env.VAYRAXA_SMOKE_PASSWORD ?? "";

if (!apiBase.startsWith("https://") && !apiBase.startsWith("http://localhost")) {
  throw new Error("VAYRAXA_COLLAB_API_URL must be HTTPS (or localhost for development).");
}

async function readJson(response: Response) {
  const text = await response.text();
  let json: any = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      // Keep raw text for diagnostic output.
    }
  }
  return { text, json };
}

function assertStatus(actual: number, expected: number, label: string) {
  if (actual !== expected) {
    throw new Error(`${label}: expected HTTP ${expected}, got ${actual}`);
  }
}

async function verifyHealth() {
  const response = await fetch(`${apiBase}/health`, {
    headers: { "X-VAYRAXA-Smoke": clientId },
  });
  assertStatus(response.status, 200, "health");
  const { json, text } = await readJson(response);
  if (
    !json ||
    json.ok !== true ||
    json.service !== "vayraxa-collaboration" ||
    json.database !== "postgresql"
  ) {
    throw new Error(`health payload invalid: ${text}`);
  }
  console.log("[OK] HTTPS health + PostgreSQL authority");
}

async function verifyAuthRoute() {
  const response = await fetch(`${apiBase}/v1/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-VAYRAXA-Client": clientId,
    },
    body: JSON.stringify({}),
  });
  assertStatus(response.status, 400, "auth contract");
  console.log("[OK] login endpoint contract");
}

async function verifyProtectedHttp(accessToken?: string) {
  const id = projectId || "00000000-0000-0000-0000-000000000000";
  const response = await fetch(`${apiBase}/v1/projects/${id}/state`, {
    headers: accessToken
      ? {
          Authorization: `Bearer ${accessToken}`,
          "X-VAYRAXA-Client": clientId,
        }
      : { "X-VAYRAXA-Client": clientId },
  });

  if (accessToken) {
    assertStatus(response.status, 200, "authenticated project state");
    console.log("[OK] authenticated project-state endpoint");
  } else {
    assertStatus(response.status, 401, "protected API");
    console.log("[OK] protected API rejects anonymous traffic");
  }
}

async function verifyUnauthorizedWebSocket() {
  const wsBase = apiBase.replace(/^http:/, "ws:").replace(/^https:/, "wss:");
  const id = projectId || "00000000-0000-0000-0000-000000000000";
  await new Promise<void>((resolve, reject) => {
    const socket = new WebSocket(`${wsBase}/v1/projects/${id}/stream`, {
      headers: {
        Authorization: "Bearer intentionally-invalid-smoke-token",
        "X-VAYRAXA-Client": clientId,
      },
    });
    const timer = setTimeout(() => {
      socket.terminate();
      reject(new Error("WSS unauthorized check timed out."));
    }, 10000);

    socket.once("unexpected-response", (_request, response) => {
      clearTimeout(timer);
      if (response.statusCode !== 401) {
        reject(
          new Error(
            `WSS auth gate expected HTTP 401, got ${response.statusCode}`,
          ),
        );
        return;
      }
      console.log("[OK] WSS endpoint reachable and rejects invalid bearer token");
      resolve();
    });
    socket.once("open", () => {
      clearTimeout(timer);
      socket.close();
      reject(new Error("WSS endpoint accepted an invalid bearer token."));
    });
    socket.once("error", (error) => {
      // Some proxies surface the HTTP 401 only as an error. Keep this branch
      // diagnostic rather than treating generic connection failure as success.
      if (!String(error.message).includes("401")) {
        clearTimeout(timer);
        reject(error);
      }
    });
  });
}

async function loginIfConfigured(): Promise<string | undefined> {
  if (!email && !password) return undefined;
  if (!email || !password || !projectId) {
    throw new Error(
      "Credentialed smoke requires VAYRAXA_SMOKE_EMAIL, VAYRAXA_SMOKE_PASSWORD and VAYRAXA_COLLAB_PROJECT_ID together.",
    );
  }

  const response = await fetch(`${apiBase}/v1/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-VAYRAXA-Client": clientId,
    },
    body: JSON.stringify({ email, password, clientId }),
  });
  assertStatus(response.status, 200, "credentialed login");
  const { json, text } = await readJson(response);
  const accessToken = json?.accessToken;
  if (typeof accessToken !== "string" || accessToken.length < 20) {
    throw new Error(`login response missing accessToken: ${text}`);
  }
  console.log("[OK] credentialed login");
  return accessToken;
}

async function verifyAuthorizedWebSocket(accessToken: string) {
  const wsBase = apiBase.replace(/^http:/, "ws:").replace(/^https:/, "wss:");
  await new Promise<void>((resolve, reject) => {
    const socket = new WebSocket(
      `${wsBase}/v1/projects/${projectId}/stream`,
      {
        headers: {
          Authorization: `Bearer ${accessToken}`,
          "X-VAYRAXA-Client": clientId,
        },
      },
    );
    const timer = setTimeout(() => {
      socket.terminate();
      reject(new Error("Authenticated WSS stream timed out."));
    }, 10000);

    socket.once("message", (data) => {
      clearTimeout(timer);
      const message = JSON.parse(data.toString());
      if (message.type !== "stream.ready") {
        socket.close();
        reject(new Error(`unexpected WSS first message: ${data.toString()}`));
        return;
      }
      socket.close(1000, "smoke complete");
      console.log("[OK] authenticated WSS stream.ready");
      resolve();
    });
    socket.once("error", (error) => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

await verifyHealth();
await verifyAuthRoute();

const accessToken = await loginIfConfigured();
await verifyProtectedHttp(accessToken);

if (accessToken) {
  await verifyAuthorizedWebSocket(accessToken);
} else {
  await verifyUnauthorizedWebSocket();
}

console.log("R56 cloud smoke test passed.");
