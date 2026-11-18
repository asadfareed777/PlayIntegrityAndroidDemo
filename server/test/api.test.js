import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { startServer, signedBody, newBody } from './helpers.js';
import { encodeMockToken } from '../src/integrity/decoders.js';

describe('POST /v1/config — flowchart branches', () => {
  let srv;
  before(async () => { srv = await startServer({ REQUIRE_LICENSING: 'true' }); });
  after(async () => { await srv.close(); });

  test('T01 happy path → 200 with sensitive config', async () => {
    const r = await srv.post(signedBody());
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.equal(r.json.config.values.nativeApiKey, 'k');
    assert.equal(r.json.config.ttlSeconds, 3600);
    assert.equal(r.headers.get('cache-control'), 'no-store');
  });

  test('T02 missing fields → 400 BAD_REQUEST', async () => {
    const r = await srv.post({ requestId: 'abc' });
    assert.equal(r.status, 400);
    assert.equal(r.json.code, 'BAD_REQUEST');
  });

  test('T03 malformed JSON → 400 BAD_REQUEST', async () => {
    const r = await srv.post(null, { raw: '{not json' });
    assert.equal(r.status, 400);
    assert.equal(r.json.code, 'BAD_REQUEST');
  });

  test('T04 Google decode unavailable → 503 + Retry-After', async () => {
    const r = await srv.post({ ...newBody(), integrityToken: 'mock.unavailable' });
    assert.equal(r.status, 503);
    assert.equal(r.json.code, 'INTEGRITY_UNAVAILABLE');
    assert.ok(Number(r.headers.get('retry-after')) > 0);
  });

  test('T05 Google rejects token → 403 INVALID_TOKEN', async () => {
    const r = await srv.post({ ...newBody(), integrityToken: 'mock.invalid' });
    assert.equal(r.status, 403);
    assert.equal(r.json.code, 'INVALID_TOKEN');
  });

  test('T06 token for another package → 403 PACKAGE_MISMATCH', async () => {
    const r = await srv.post(signedBody((p) => { p.requestDetails.requestPackageName = 'com.evil'; }));
    assert.equal(r.json.code, 'PACKAGE_MISMATCH');
    assert.equal(r.status, 403);
  });

  test('T07 body tampered after token minted → 403 REQUEST_MISMATCH', async () => {
    const body = signedBody();
    body.configScope = 'admin';
    const r = await srv.post(body);
    assert.equal(r.status, 403);
    assert.equal(r.json.code, 'REQUEST_MISMATCH');
  });

  test('T08 stale token → 403 TOKEN_EXPIRED', async () => {
    const r = await srv.post(signedBody((p) => { p.requestDetails.timestampMillis = String(Date.now() - 600_000); }));
    assert.equal(r.json.code, 'TOKEN_EXPIRED');
  });

  test('T09 token from the future beyond skew → 403 TOKEN_EXPIRED', async () => {
    const r = await srv.post(signedBody((p) => { p.requestDetails.timestampMillis = String(Date.now() + 600_000); }));
    assert.equal(r.json.code, 'TOKEN_EXPIRED');
  });

  test('T10 UNRECOGNIZED_VERSION (modified app) → 403 APP_NOT_RECOGNIZED', async () => {
    const r = await srv.post(signedBody((p) => { p.appIntegrity = { appRecognitionVerdict: 'UNRECOGNIZED_VERSION' }; }));
    assert.equal(r.json.code, 'APP_NOT_RECOGNIZED');
  });

  test('T11 UNEVALUATED app verdict → 403 APP_NOT_RECOGNIZED', async () => {
    const r = await srv.post(signedBody((p) => { p.appIntegrity = { appRecognitionVerdict: 'UNEVALUATED' }; }));
    assert.equal(r.json.code, 'APP_NOT_RECOGNIZED');
  });

  test('T12 re-signed APK → 403 CERTIFICATE_MISMATCH', async () => {
    const r = await srv.post(signedBody((p) => { p.appIntegrity.certificateSha256Digest = ['ZZZZ']; }));
    assert.equal(r.json.code, 'CERTIFICATE_MISMATCH');
  });

  test('T13 basic integrity only (rooted / unlocked bootloader) → 403 DEVICE_UNTRUSTED', async () => {
    const r = await srv.post(signedBody((p) => { p.deviceIntegrity.deviceRecognitionVerdict = ['MEETS_BASIC_INTEGRITY']; }));
    assert.equal(r.json.code, 'DEVICE_UNTRUSTED');
  });

  test('T14 empty device verdict (emulator / hooked) → 403 DEVICE_UNTRUSTED', async () => {
    const r = await srv.post(signedBody((p) => { p.deviceIntegrity = {}; }));
    assert.equal(r.json.code, 'DEVICE_UNTRUSTED');
  });

  test('T15 UNLICENSED with licensing required → 403 NOT_LICENSED', async () => {
    const r = await srv.post(signedBody((p) => { p.accountDetails.appLicensingVerdict = 'UNLICENSED'; }));
    assert.equal(r.json.code, 'NOT_LICENSED');
  });

  test('T16 replay of a successful request → 403 REPLAYED_REQUEST', async () => {
    const body = signedBody();
    assert.equal((await srv.post(body)).status, 200);
    const again = await srv.post(body);
    assert.equal(again.status, 403);
    assert.equal(again.json.code, 'REPLAYED_REQUEST');
  });

  test('T17 strong integrity device also passes', async () => {
    const r = await srv.post(signedBody((p) => { p.deviceIntegrity.deviceRecognitionVerdict.push('MEETS_STRONG_INTEGRITY'); }));
    assert.equal(r.status, 200);
  });

  test('T18 failure responses never leak config values', async () => {
    const r = await srv.post(signedBody((p) => { p.deviceIntegrity = {}; }));
    assert.equal(r.json.config, undefined);
    assert.equal(JSON.stringify(r.json).includes('nativeApiKey'), false);
  });
});

describe('policy switches', () => {
  test('T19 licensing not required → UNLICENSED still passes', async () => {
    const srv = await startServer({ REQUIRE_LICENSING: 'false' });
    try {
      const r = await srv.post(signedBody((p) => { p.accountDetails.appLicensingVerdict = 'UNLICENSED'; }));
      assert.equal(r.status, 200);
    } finally { await srv.close(); }
  });

  test('T20 REQUIRED_DEVICE_VERDICT=MEETS_STRONG_INTEGRITY rejects device-only', async () => {
    const srv = await startServer({ REQUIRED_DEVICE_VERDICT: 'MEETS_STRONG_INTEGRITY' });
    try {
      const r = await srv.post(signedBody());
      assert.equal(r.json.code, 'DEVICE_UNTRUSTED');
    } finally { await srv.close(); }
  });
});

describe('rate limiting', () => {
  test('T21 exceeding RATE_LIMIT_MAX → 429 RATE_LIMITED + Retry-After', async () => {
    const srv = await startServer({ RATE_LIMIT_MAX: '2' });
    try {
      await srv.post(signedBody());
      await srv.post(signedBody());
      const r = await srv.post(signedBody());
      assert.equal(r.status, 429);
      assert.equal(r.json.code, 'RATE_LIMITED');
      assert.ok(Number(r.headers.get('retry-after')) >= 1);
    } finally { await srv.close(); }
  });
});

test('T22 decoder throwing an unexpected error → 503, not 500', async () => {
  const srv = await startServer({}, { decoder: { decode: async () => { throw new Error('socket hang up'); } } });
  try {
    const r = await srv.post({ ...newBody(), integrityToken: encodeMockToken({}) });
    assert.equal(r.status, 503);
  } finally { await srv.close(); }
});
