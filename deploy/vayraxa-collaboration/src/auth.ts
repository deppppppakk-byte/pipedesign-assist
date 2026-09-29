import crypto from "node:crypto";

export const PASSWORD_ALGORITHM = "SCRYPT_V1";
export const SCRYPT_KEY_LENGTH = 64;
export const SCRYPT_COST = 32768;
export const SCRYPT_BLOCK_SIZE = 8;
export const SCRYPT_PARALLELIZATION = 1;
export const SCRYPT_MAX_MEMORY = 128 * 1024 * 1024;

export type PasswordMaterial = {
  salt: Buffer;
  hash: Buffer;
  algorithm: typeof PASSWORD_ALGORITHM;
};

export function fingerprintToken(token: string): string {
  return crypto.createHash("sha256").update(token, "utf8").digest("hex");
}

export function randomOpaqueToken(bytes = 32): string {
  return crypto.randomBytes(bytes).toString("base64url");
}

export function randomUuid(): string {
  return crypto.randomUUID();
}

export function clampAccessTokenSeconds(value: number): number {
  if (!Number.isFinite(value)) return 900;
  return Math.max(300, Math.min(Math.trunc(value), 3600));
}

export function clampRefreshTokenSeconds(value: number): number {
  if (!Number.isFinite(value)) return 30 * 24 * 60 * 60;
  return Math.max(24 * 60 * 60, Math.min(Math.trunc(value), 90 * 24 * 60 * 60));
}

function scryptPassword(password: string, salt: Buffer): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    crypto.scrypt(
      password,
      salt,
      SCRYPT_KEY_LENGTH,
      {
        N: SCRYPT_COST,
        r: SCRYPT_BLOCK_SIZE,
        p: SCRYPT_PARALLELIZATION,
        maxmem: SCRYPT_MAX_MEMORY,
      },
      (error, derivedKey) => {
        if (error) {
          reject(error);
          return;
        }
        resolve(Buffer.from(derivedKey));
      },
    );
  });
}

export async function hashPassword(password: string): Promise<PasswordMaterial> {
  const salt = crypto.randomBytes(24);
  const hash = await scryptPassword(password, salt);
  return {
    salt,
    hash,
    algorithm: PASSWORD_ALGORITHM,
  };
}

export async function verifyPassword(
  password: string,
  salt: Buffer,
  expectedHash: Buffer,
  algorithm: string,
): Promise<boolean> {
  if (algorithm !== PASSWORD_ALGORITHM) return false;
  const actualHash = await scryptPassword(password, salt);
  return (
    actualHash.length === expectedHash.length &&
    crypto.timingSafeEqual(actualHash, expectedHash)
  );
}

export function validPasswordPolicy(password: string): boolean {
  return (
    password.length >= 12 &&
    password.length <= 256 &&
    /[A-Za-z]/.test(password) &&
    /[0-9]/.test(password)
  );
}
