import { normalizeCertDigest } from './integrity/verdictValidator.js';

function bool(value, fallback) {
  if (value === undefined || value === '') return fallback;
  return ['1', 'true', 'yes'].includes(String(value).toLowerCase());
}

function int(value, fallback) {
  const n = Number.parseInt(value ?? '', 10);
  return Number.isFinite(n) ? n : fallback;
}

function list(value) {
  return (value ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

/** Builds and validates configuration from an env-like object. Throws on unsafe combinations. */
export function loadConfig(env = process.env) {
  const config = {
    port: int(env.PORT, 8080),
    nodeEnv: env.NODE_ENV ?? 'development',
    integrityMode: env.INTEGRITY_MODE ?? 'google',
    expectedPackageName: env.EXPECTED_PACKAGE_NAME ?? '',
    allowedCertDigests: list(env.ALLOWED_CERT_DIGESTS).map(normalizeCertDigest),
    allowedAppVerdicts: list(env.ALLOWED_APP_VERDICTS || 'PLAY_RECOGNIZED'),
    requiredDeviceVerdict: env.REQUIRED_DEVICE_VERDICT || 'MEETS_DEVICE_INTEGRITY',
    requireLicensing: bool(env.REQUIRE_LICENSING, false),
    maxTokenAgeMs: int(env.MAX_TOKEN_AGE_MS, 120_000),
    maxClockSkewMs: int(env.MAX_CLOCK_SKEW_MS, 10_000),
    rateLimitWindowMs: int(env.RATE_LIMIT_WINDOW_MS, 60_000),
    rateLimitMax: int(env.RATE_LIMIT_MAX, 20),
    sensitiveConfig: JSON.parse(env.SENSITIVE_CONFIG_JSON || '{}'),
    configTtlSeconds: int(env.CONFIG_TTL_SECONDS, 3600),
    configCacheable: bool(env.CONFIG_CACHEABLE, true),
    trustProxy: bool(env.TRUST_PROXY, false),
  };

  const errors = [];
  if (!config.expectedPackageName) errors.push('EXPECTED_PACKAGE_NAME is required');
  if (!['google', 'mock'].includes(config.integrityMode)) errors.push('INTEGRITY_MODE must be google|mock');
  if (config.integrityMode === 'mock' && config.nodeEnv === 'production') {
    errors.push('INTEGRITY_MODE=mock is forbidden when NODE_ENV=production');
  }
  if (config.nodeEnv === 'production') {
    if (config.allowedCertDigests.length === 0) errors.push('ALLOWED_CERT_DIGESTS is required in production');
    if (config.allowedAppVerdicts.some((v) => v !== 'PLAY_RECOGNIZED')) {
      errors.push('ALLOWED_APP_VERDICTS must be PLAY_RECOGNIZED in production');
    }
  }
  if (typeof config.sensitiveConfig !== 'object' || Array.isArray(config.sensitiveConfig)) {
    errors.push('SENSITIVE_CONFIG_JSON must be a JSON object');
  }
  if (errors.length) throw new Error(`Invalid configuration:\n - ${errors.join('\n - ')}`);
  return Object.freeze(config);
}
