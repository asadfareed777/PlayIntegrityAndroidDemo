import { test } from 'node:test';
import assert from 'node:assert/strict';
import { canonicalPayload, sha256Hex, expectedRequestHash } from '../src/integrity/requestHash.js';
import { normalizeCertDigest } from '../src/integrity/verdictValidator.js';
import { loadConfig } from '../src/config.js';
import { checkBody } from '../src/middleware/validateRequest.js';
import { InMemoryReplayStore } from '../src/replayStore.js';

// Same fixture as app/src/test/.../RequestHasherTest.kt — if one changes, both must.
const FIXTURE = {
  requestId: '00112233445566778899aabbccddeeff',
  timestampMillis: 1700000000000,
  packageName: 'com.example.playintegrity',
  appVersionCode: 1,
  configScope: 'default',
};

test('U01 canonical payload format is stable', () => {
  assert.equal(
    canonicalPayload(FIXTURE),
    'v1\nPOST\n/v1/config\n00112233445566778899aabbccddeeff\n1700000000000\ncom.example.playintegrity\n1\ndefault',
  );
});

test('U02 sha256Hex known vector', () => {
  assert.equal(sha256Hex('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('U03 requestHash is 64 lowercase hex and matches the Kotlin fixture', () => {
  const h = expectedRequestHash(FIXTURE);
  assert.match(h, /^[0-9a-f]{64}$/);
  assert.equal(h, 'af0127633ddef9f563a1a9c953ee92883e6fa9ce59ce4eaf31bd239b0319a269');
});

test('U04 cert digest normalization: colon-hex, hex, base64 → base64url', () => {
  const expected = Buffer.from('ab'.repeat(32), 'hex').toString('base64url');
  assert.equal(normalizeCertDigest('AB:'.repeat(31) + 'AB'), expected);
  assert.equal(normalizeCertDigest('ab'.repeat(32)), expected);
  assert.equal(normalizeCertDigest(Buffer.from('ab'.repeat(32), 'hex').toString('base64')), expected);
});

test('U05 config: mock mode is refused in production', () => {
  assert.throws(
    () => loadConfig({ NODE_ENV: 'production', INTEGRITY_MODE: 'mock', EXPECTED_PACKAGE_NAME: 'a.b', ALLOWED_CERT_DIGESTS: 'x' }),
    /forbidden/,
  );
});

test('U06 config: production requires cert digests and PLAY_RECOGNIZED only', () => {
  assert.throws(() => loadConfig({ NODE_ENV: 'production', EXPECTED_PACKAGE_NAME: 'a.b' }), /ALLOWED_CERT_DIGESTS/);
  assert.throws(
    () => loadConfig({ NODE_ENV: 'production', EXPECTED_PACKAGE_NAME: 'a.b', ALLOWED_CERT_DIGESTS: 'x', ALLOWED_APP_VERDICTS: 'PLAY_RECOGNIZED,UNRECOGNIZED_VERSION' }),
    /PLAY_RECOGNIZED/,
  );
});

test('U07 request body validation', () => {
  assert.equal(checkBody({ ...FIXTURE, integrityToken: 'x'.repeat(100) }), null);
  assert.match(checkBody({ ...FIXTURE, requestId: 'XYZ', integrityToken: 'x'.repeat(100) }), /requestId/);
  assert.match(checkBody({ ...FIXTURE, configScope: '../etc', integrityToken: 'x'.repeat(100) }), /configScope/);
  assert.match(checkBody({ ...FIXTURE }), /integrityToken/);
  assert.match(checkBody([]), /JSON object/);
});

test('U08 replay store expires ids after ttl', () => {
  let t = 0;
  const store = new InMemoryReplayStore({ ttlMs: 1000, now: () => t });
  assert.equal(store.markUsed('a'), true);
  assert.equal(store.markUsed('a'), false);
  t = 1001;
  assert.equal(store.has('a'), false);
  assert.equal(store.markUsed('a'), true);
  store.close();
});
