import assert from "node:assert/strict";
import {
  clampAccessTokenSeconds,
  clampRefreshTokenSeconds,
  fingerprintToken,
  hashPassword,
  randomOpaqueToken,
  verifyPassword,
} from "./auth.js";

const password = "Vayraxa-Test-Password-42";
const material = await hashPassword(password);

assert.equal(
  await verifyPassword(
    password,
    material.salt,
    material.hash,
    material.algorithm,
  ),
  true,
);
assert.equal(
  await verifyPassword(
    "wrong-password-42",
    material.salt,
    material.hash,
    material.algorithm,
  ),
  false,
);

const tokenA = randomOpaqueToken();
const tokenB = randomOpaqueToken();
assert.notEqual(tokenA, tokenB);
assert.equal(fingerprintToken(tokenA).length, 64);
assert.equal(fingerprintToken(tokenA), fingerprintToken(tokenA));
assert.notEqual(fingerprintToken(tokenA), fingerprintToken(tokenB));

assert.equal(clampAccessTokenSeconds(1), 300);
assert.equal(clampAccessTokenSeconds(999999), 3600);
assert.equal(clampRefreshTokenSeconds(1), 86400);
assert.equal(clampRefreshTokenSeconds(999999999), 90 * 24 * 60 * 60);

console.log("R56 authentication primitive self-test passed.");
