import { createHash } from 'node:crypto';

/**
 * MUST stay byte-for-byte identical to
 * app/src/main/java/com/example/playintegrity/integrity/RequestHasher.kt
 */
export const HASH_VERSION = 'v1';
export const METHOD = 'POST';
export const PATH = '/v1/config';

export function canonicalPayload({ requestId, timestampMillis, packageName, appVersionCode, configScope }) {
  return [
    HASH_VERSION,
    METHOD,
    PATH,
    requestId,
    String(timestampMillis),
    packageName,
    String(appVersionCode),
    configScope,
  ].join('\n');
}

export function sha256Hex(input) {
  return createHash('sha256').update(input, 'utf8').digest('hex');
}

/** Recomputes the requestHash the client SHOULD have bound into its token. */
export function expectedRequestHash(body) {
  return sha256Hex(canonicalPayload(body));
}
