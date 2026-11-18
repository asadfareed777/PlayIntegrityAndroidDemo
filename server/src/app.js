import express from 'express';
import { createRateLimiter } from './middleware/rateLimiter.js';
import { validateConfigRequest } from './middleware/validateRequest.js';
import { expectedRequestHash } from './integrity/requestHash.js';
import { validateVerdict } from './integrity/verdictValidator.js';
import { DecodeError } from './integrity/decoders.js';
import { InMemoryReplayStore } from './replayStore.js';

/**
 * Builds the Express app. Everything with side effects is injected so tests can swap it.
 *
 * @param {object} deps
 * @param {ReturnType<import('./config.js').loadConfig>} deps.config
 * @param {{decode(token:string):Promise<object>}} deps.decoder
 * @param {() => number} [deps.now]
 * @param {(event:object) => void} [deps.log]
 */
export function createApp({ config, decoder, now = () => Date.now(), log = defaultLog }) {
  const app = express();
  app.disable('x-powered-by');
  if (config.trustProxy) app.set('trust proxy', true);

  const rateLimiter = createRateLimiter({ windowMs: config.rateLimitWindowMs, max: config.rateLimitMax, now });
  const replayStore = new InMemoryReplayStore({
    ttlMs: config.maxTokenAgeMs + config.maxClockSkewMs + 60_000,
    now,
  });

  app.get('/healthz', (_req, res) => res.json({ ok: true }));

  app.post(
    '/v1/config',
    rateLimiter,
    express.json({ limit: '32kb' }),
    validateConfigRequest,
    async (req, res) => {
      const body = req.body;
      const started = now();
      const audit = { route: '/v1/config', requestId: body.requestId, ip: req.ip, appVersionCode: body.appVersionCode };

      // Cheap replay pre-check before spending a Google API call.
      if (replayStore.has(body.requestId)) {
        log({ ...audit, result: 'REPLAYED_REQUEST' });
        return res.status(403).json({ ok: false, code: 'REPLAYED_REQUEST', message: 'requestId already used' });
      }

      // "Backend recalculates expected requestHash" — from the body WE received, never from the token.
      const expectedHash = expectedRequestHash({ ...body, packageName: config.expectedPackageName });

      // "Backend sends token to Google" / "Google decoding successful?"
      let payload;
      try {
        payload = await decoder.decode(body.integrityToken);
      } catch (err) {
        if (err instanceof DecodeError && err.kind === 'INVALID_TOKEN') {
          log({ ...audit, result: 'INVALID_TOKEN', googleStatus: err.status });
          return res.status(403).json({ ok: false, code: 'INVALID_TOKEN', message: 'Integrity token could not be verified' });
        }
        log({ ...audit, result: 'DECODE_UNAVAILABLE', error: err.message, googleStatus: err.status });
        res.setHeader('Retry-After', String(err.retryAfterSeconds ?? 30));
        return res.status(503).json({ ok: false, code: 'INTEGRITY_UNAVAILABLE', message: 'Try again later' });
      }

      // Package → hash → timestamp → app → cert → device → licensing.
      const verdict = validateVerdict(
        payload,
        {
          expectedPackageName: config.expectedPackageName,
          expectedRequestHash: expectedHash,
          maxTokenAgeMs: config.maxTokenAgeMs,
          maxClockSkewMs: config.maxClockSkewMs,
          allowedAppVerdicts: config.allowedAppVerdicts,
          allowedCertDigests: config.allowedCertDigests,
          requiredDeviceVerdict: config.requiredDeviceVerdict,
          requireLicensing: config.requireLicensing,
        },
        now(),
      );

      if (!verdict.ok) {
        log({ ...audit, result: verdict.code, detail: verdict.message, ms: now() - started });
        // Do not leak verdict details to the client; the code is enough for UX.
        return res.status(verdict.status).json({ ok: false, code: verdict.code, message: 'Integrity check failed' });
      }

      // Token is valid and bound to this requestId: burn the id (atomic check-and-set).
      if (!replayStore.markUsed(body.requestId)) {
        log({ ...audit, result: 'REPLAYED_REQUEST' });
        return res.status(403).json({ ok: false, code: 'REPLAYED_REQUEST', message: 'requestId already used' });
      }

      log({ ...audit, result: 'OK', ...verdict.summary, ms: now() - started });
      res.setHeader('Cache-Control', 'no-store');
      return res.json({
        ok: true,
        config: {
          values: config.sensitiveConfig,
          issuedAt: now(),
          ttlSeconds: config.configTtlSeconds,
          cacheable: config.configCacheable,
        },
      });
    },
  );

  // Malformed JSON → 400, everything else → 500 without stack traces.
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err.type === 'entity.parse.failed' || err.type === 'entity.too.large') {
      return res.status(400).json({ ok: false, code: 'BAD_REQUEST', message: 'Invalid JSON body' });
    }
    log({ result: 'INTERNAL_ERROR', error: err.message });
    return res.status(500).json({ ok: false, code: 'INTERNAL_ERROR', message: 'Internal error' });
  });

  app.close = () => {
    rateLimiter.close();
    replayStore.close();
  };
  return app;
}

function defaultLog(event) {
  // Never log the integrity token or config values.
  console.log(JSON.stringify({ ts: new Date().toISOString(), ...event }));
}
