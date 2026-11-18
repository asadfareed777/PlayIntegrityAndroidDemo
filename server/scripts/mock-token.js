#!/usr/bin/env node
/**
 * Generates a complete request body with a MOCK integrity token for a server running
 * with INTEGRITY_MODE=mock. Pipe the output straight into curl.
 *
 *   node scripts/mock-token.js [scenario] [--package com.example.playintegrity]
 *
 * Scenarios: pass (default), wrong-package, wrong-hash, expired, unrecognized, bad-cert,
 *            basic-only, no-verdict, unlicensed, strong, google-down, google-invalid
 */
import { randomBytes } from 'node:crypto';
import { expectedRequestHash } from '../src/integrity/requestHash.js';
import { encodeMockToken } from '../src/integrity/decoders.js';

const args = process.argv.slice(2);
const scenario = args.find((a) => !a.startsWith('--')) ?? 'pass';
const pkgIdx = args.indexOf('--package');
const packageName = pkgIdx >= 0 ? args[pkgIdx + 1] : process.env.EXPECTED_PACKAGE_NAME ?? 'com.example.playintegrity';
const cert = process.env.MOCK_CERT_DIGEST ?? 'mock-cert-digest';

const body = {
  requestId: randomBytes(16).toString('hex'),
  timestampMillis: Date.now(),
  packageName,
  appVersionCode: 1,
  configScope: 'default',
};

const payload = {
  requestDetails: {
    requestPackageName: packageName,
    requestHash: expectedRequestHash(body),
    timestampMillis: String(Date.now()),
  },
  appIntegrity: {
    appRecognitionVerdict: 'PLAY_RECOGNIZED',
    packageName,
    certificateSha256Digest: [cert],
    versionCode: '1',
  },
  deviceIntegrity: { deviceRecognitionVerdict: ['MEETS_BASIC_INTEGRITY', 'MEETS_DEVICE_INTEGRITY'] },
  accountDetails: { appLicensingVerdict: 'LICENSED' },
};

switch (scenario) {
  case 'pass': break;
  case 'wrong-package': payload.requestDetails.requestPackageName = 'com.evil.clone'; break;
  case 'wrong-hash': payload.requestDetails.requestHash = 'f'.repeat(64); break;
  case 'expired': payload.requestDetails.timestampMillis = String(Date.now() - 10 * 60_000); break;
  case 'unrecognized': payload.appIntegrity.appRecognitionVerdict = 'UNRECOGNIZED_VERSION'; break;
  case 'bad-cert': payload.appIntegrity.certificateSha256Digest = ['attacker-cert']; break;
  case 'basic-only': payload.deviceIntegrity.deviceRecognitionVerdict = ['MEETS_BASIC_INTEGRITY']; break;
  case 'no-verdict': payload.deviceIntegrity = {}; break;
  case 'unlicensed': payload.accountDetails.appLicensingVerdict = 'UNLICENSED'; break;
  case 'strong': payload.deviceIntegrity.deviceRecognitionVerdict.push('MEETS_STRONG_INTEGRITY'); break;
  case 'google-down': body.integrityToken = 'mock.unavailable'; break;
  case 'google-invalid': body.integrityToken = 'mock.invalid'; break;
  default:
    console.error(`Unknown scenario "${scenario}"`);
    process.exit(1);
}

body.integrityToken ??= encodeMockToken(payload);
process.stdout.write(JSON.stringify(body));
