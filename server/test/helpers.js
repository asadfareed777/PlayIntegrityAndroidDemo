import { randomBytes } from 'node:crypto';
import { createApp } from '../src/app.js';
import { loadConfig } from '../src/config.js';
import { MockIntegrityDecoder, encodeMockToken } from '../src/integrity/decoders.js';
import { expectedRequestHash } from '../src/integrity/requestHash.js';

export const PKG = 'com.example.playintegrity';
// Colon-hex as shown in Play Console; the server normalizes it to base64url.
export const CERT_HEX = 'AB:'.repeat(31) + 'AB';
export const CERT_B64URL = Buffer.from('ab'.repeat(32), 'hex').toString('base64url');

export function testConfig(overrides = {}) {
  return loadConfig({
    NODE_ENV: 'test',
    INTEGRITY_MODE: 'mock',
    EXPECTED_PACKAGE_NAME: PKG,
    ALLOWED_CERT_DIGESTS: CERT_HEX,
    REQUIRE_LICENSING: 'false',
    RATE_LIMIT_MAX: '1000',
    SENSITIVE_CONFIG_JSON: JSON.stringify({ nativeApiKey: 'k', featureFlags: 'a,b' }),
    ...overrides,
  });
}

export function newBody(extra = {}) {
  return {
    requestId: randomBytes(16).toString('hex'),
    timestampMillis: Date.now(),
    packageName: PKG,
    appVersionCode: 1,
    configScope: 'default',
    ...extra,
  };
}

/** A payload that passes every check for [body]; [mutate] lets a test break one field. */
export function passingPayload(body, mutate = (p) => p) {
  const p = {
    requestDetails: {
      requestPackageName: PKG,
      requestHash: expectedRequestHash(body),
      timestampMillis: String(Date.now()),
    },
    appIntegrity: {
      appRecognitionVerdict: 'PLAY_RECOGNIZED',
      packageName: PKG,
      certificateSha256Digest: [CERT_B64URL],
      versionCode: '1',
    },
    deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_BASIC_INTEGRITY', 'MEETS_DEVICE_INTEGRITY'] },
    accountDetails: { appLicensingVerdict: 'LICENSED' },
  };
  return mutate(p) ?? p;
}

export function signedBody(mutate, extra) {
  const body = newBody(extra);
  return { ...body, integrityToken: encodeMockToken(passingPayload(body, mutate)) };
}

/** Starts the app on an ephemeral port; returns { post, close }. */
export async function startServer(configOverrides = {}, deps = {}) {
  const app = createApp({
    config: testConfig(configOverrides),
    decoder: deps.decoder ?? new MockIntegrityDecoder(),
    log: () => {},
    ...deps,
  });
  const server = await new Promise((resolve) => {
    const s = app.listen(0, '127.0.0.1', () => resolve(s));
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  return {
    base,
    async post(body, { raw } = {}) {
      const res = await fetch(`${base}/v1/config`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: raw ?? JSON.stringify(body),
      });
      return { status: res.status, headers: res.headers, json: await res.json() };
    },
    close: () =>
      new Promise((resolve) => {
        app.close();
        server.close(resolve);
        server.closeAllConnections?.(); // drop fetch keep-alive sockets so tests finish fast
      }),
  };
}
