const REQUEST_ID = /^[0-9a-f]{32}$/;
const PACKAGE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;
const SCOPE = /^[a-z0-9_-]{1,32}$/;
const MAX_TOKEN_LENGTH = 16_384;

/** Returns null when valid, otherwise a human-readable reason. */
export function checkBody(body) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) return 'Body must be a JSON object';
  const { requestId, timestampMillis, packageName, appVersionCode, configScope, integrityToken } = body;
  if (typeof requestId !== 'string' || !REQUEST_ID.test(requestId)) return 'requestId must be 32 lowercase hex chars';
  if (!Number.isSafeInteger(timestampMillis) || timestampMillis <= 0) return 'timestampMillis must be a positive integer';
  if (typeof packageName !== 'string' || !PACKAGE.test(packageName)) return 'packageName is invalid';
  if (!Number.isSafeInteger(appVersionCode) || appVersionCode <= 0) return 'appVersionCode must be a positive integer';
  if (typeof configScope !== 'string' || !SCOPE.test(configScope)) return 'configScope is invalid';
  if (typeof integrityToken !== 'string' || integrityToken.length < 8 || integrityToken.length > MAX_TOKEN_LENGTH) {
    return 'integrityToken is missing or has an invalid length';
  }
  return null;
}

/** "Request valid?" box of the flowchart → 400 on failure. */
export function validateConfigRequest(req, res, next) {
  const reason = checkBody(req.body);
  if (reason) return res.status(400).json({ ok: false, code: 'BAD_REQUEST', message: reason });
  next();
}
