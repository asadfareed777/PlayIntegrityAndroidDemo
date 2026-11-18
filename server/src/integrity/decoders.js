import { GoogleAuth } from 'google-auth-library';

/**
 * A decoder turns an integrity token into `tokenPayloadExternal`.
 * It throws DecodeError so the route can tell "token is bad" (403) from "Google is down" (503).
 */
export class DecodeError extends Error {
  /** @param {'INVALID_TOKEN'|'UNAVAILABLE'} kind */
  constructor(kind, message, { status, retryAfterSeconds } = {}) {
    super(message);
    this.kind = kind;
    this.status = status;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * Real decoder: POST https://playintegrity.googleapis.com/v1/{packageName}:decodeIntegrityToken
 * Credentials: GOOGLE_APPLICATION_CREDENTIALS or Application Default Credentials.
 * The service account's Cloud project must be the one linked to the app in Play Console.
 */
export class GoogleIntegrityDecoder {
  constructor({ packageName, timeoutMs = 10_000, auth } = {}) {
    this.packageName = packageName;
    this.timeoutMs = timeoutMs;
    this.auth = auth ?? new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/playintegrity'] });
    this.clientPromise = null;
  }

  async decode(token) {
    this.clientPromise ??= this.auth.getClient();
    const client = await this.clientPromise;
    const url = `https://playintegrity.googleapis.com/v1/${encodeURIComponent(this.packageName)}:decodeIntegrityToken`;
    try {
      const res = await client.request({
        url,
        method: 'POST',
        data: { integrity_token: token },
        timeout: this.timeoutMs,
        retry: false,
      });
      const payload = res.data?.tokenPayloadExternal;
      if (!payload) throw new DecodeError('UNAVAILABLE', 'Empty decode response');
      return payload;
    } catch (err) {
      if (err instanceof DecodeError) throw err;
      const status = err?.response?.status;
      // 400 INVALID_ARGUMENT = malformed/forged/expired-provider token, or a token for another package.
      if (status === 400 || status === 404) {
        throw new DecodeError('INVALID_TOKEN', 'Google rejected the integrity token', { status });
      }
      // 401/403 here = OUR credentials/project linking is broken. Surface as 503 and alert ops.
      const retryAfter = Number.parseInt(err?.response?.headers?.['retry-after'] ?? '', 10);
      throw new DecodeError('UNAVAILABLE', `Decode failed: ${err.message}`, {
        status,
        retryAfterSeconds: Number.isFinite(retryAfter) ? retryAfter : 30,
      });
    }
  }
}

/**
 * LOCAL TESTING ONLY. Token format: "mock." + base64url(JSON tokenPayloadExternal).
 * Special tokens: "mock.unavailable" → simulates Google 5xx, "mock.invalid" → simulates Google 400.
 * Refused by config.js when NODE_ENV=production.
 */
export class MockIntegrityDecoder {
  async decode(token) {
    if (token === 'mock.unavailable') {
      throw new DecodeError('UNAVAILABLE', 'Simulated Google outage', { status: 503, retryAfterSeconds: 5 });
    }
    if (typeof token !== 'string' || !token.startsWith('mock.') || token === 'mock.invalid') {
      throw new DecodeError('INVALID_TOKEN', 'Not a valid mock token', { status: 400 });
    }
    try {
      return JSON.parse(Buffer.from(token.slice(5), 'base64url').toString('utf8'));
    } catch {
      throw new DecodeError('INVALID_TOKEN', 'Malformed mock token', { status: 400 });
    }
  }
}

export function encodeMockToken(payload) {
  return 'mock.' + Buffer.from(JSON.stringify(payload), 'utf8').toString('base64url');
}
