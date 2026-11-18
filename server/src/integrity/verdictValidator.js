import { timingSafeEqual } from 'node:crypto';

/** Error codes returned to the app. Keep in sync with README sections 7 and 8. */
export const Codes = Object.freeze({
  PACKAGE_MISMATCH: 'PACKAGE_MISMATCH',
  REQUEST_MISMATCH: 'REQUEST_MISMATCH',
  TOKEN_EXPIRED: 'TOKEN_EXPIRED',
  APP_NOT_RECOGNIZED: 'APP_NOT_RECOGNIZED', // "403 App Modified" in the flowchart
  CERTIFICATE_MISMATCH: 'CERTIFICATE_MISMATCH',
  DEVICE_UNTRUSTED: 'DEVICE_UNTRUSTED',
  NOT_LICENSED: 'NOT_LICENSED',
});

/**
 * Accepts Play Console style colon-hex ("AB:CD:..."), plain hex, or base64url and returns
 * unpadded base64url — the format used in appIntegrity.certificateSha256Digest.
 */
export function normalizeCertDigest(value) {
  const v = String(value).trim();
  const hex = v.replace(/:/g, '');
  if (/^[0-9a-fA-F]{64}$/.test(hex)) return Buffer.from(hex, 'hex').toString('base64url');
  return v.replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function safeEqual(a, b) {
  const ab = Buffer.from(String(a ?? ''), 'utf8');
  const bb = Buffer.from(String(b ?? ''), 'utf8');
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

function fail(code, message) {
  return { ok: false, status: 403, code, message };
}

/**
 * Validates a decoded `tokenPayloadExternal` in the exact order of the flowchart:
 * package → requestHash → timestamp → app recognition → signing cert → device → licensing.
 *
 * Pure function (no I/O) so every branch is unit-testable.
 */
export function validateVerdict(payload, policy, now = Date.now()) {
  const requestDetails = payload?.requestDetails ?? {};
  const appIntegrity = payload?.appIntegrity ?? {};
  const deviceIntegrity = payload?.deviceIntegrity ?? {};
  const accountDetails = payload?.accountDetails ?? {};

  // 1. Package matches?
  if (requestDetails.requestPackageName !== policy.expectedPackageName) {
    return fail(Codes.PACKAGE_MISMATCH, 'Token was issued for a different package');
  }

  // 2. requestHash matches the hash recomputed from the request body?
  if (!safeEqual(requestDetails.requestHash, policy.expectedRequestHash)) {
    return fail(Codes.REQUEST_MISMATCH, 'Token is not bound to this request');
  }

  // 3. Timestamp fresh? (Google's clock, not the client's.)
  const issuedAt = Number(requestDetails.timestampMillis);
  if (!Number.isFinite(issuedAt)) return fail(Codes.TOKEN_EXPIRED, 'Missing token timestamp');
  const age = now - issuedAt;
  if (age > policy.maxTokenAgeMs || age < -policy.maxClockSkewMs) {
    return fail(Codes.TOKEN_EXPIRED, `Token age ${age}ms outside allowed window`);
  }

  // 4. App recognition: PLAY_RECOGNIZED (UNRECOGNIZED_VERSION = modified/re-signed/sideloaded build).
  if (!policy.allowedAppVerdicts.includes(appIntegrity.appRecognitionVerdict)) {
    return fail(Codes.APP_NOT_RECOGNIZED, `appRecognitionVerdict=${appIntegrity.appRecognitionVerdict ?? 'missing'}`);
  }

  // 5. Signing certificate approved? (Skipped only when no digests configured — non-production only.)
  if (policy.allowedCertDigests.length > 0) {
    const digests = (appIntegrity.certificateSha256Digest ?? []).map(normalizeCertDigest);
    if (!digests.some((d) => policy.allowedCertDigests.includes(d))) {
      return fail(Codes.CERTIFICATE_MISMATCH, 'Signing certificate is not on the allow-list');
    }
  }

  // 6. Device integrity.
  const labels = deviceIntegrity.deviceRecognitionVerdict ?? [];
  if (!labels.includes(policy.requiredDeviceVerdict)) {
    return fail(Codes.DEVICE_UNTRUSTED, `Device verdict [${labels.join(',')}] lacks ${policy.requiredDeviceVerdict}`);
  }

  // 7. Licensing (optional).
  if (policy.requireLicensing && accountDetails.appLicensingVerdict !== 'LICENSED') {
    return fail(Codes.NOT_LICENSED, `appLicensingVerdict=${accountDetails.appLicensingVerdict ?? 'missing'}`);
  }

  return {
    ok: true,
    summary: {
      appVerdict: appIntegrity.appRecognitionVerdict,
      versionCode: appIntegrity.versionCode,
      deviceLabels: labels,
      licensing: accountDetails.appLicensingVerdict,
    },
  };
}
