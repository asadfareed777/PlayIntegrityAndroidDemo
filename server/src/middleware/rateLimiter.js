/**
 * Fixed-window per-key rate limiter (in memory).
 * TODO(prod): back this with Redis (or your gateway) so limits hold across instances.
 */
export function createRateLimiter({ windowMs, max, keyFn = (req) => req.ip, now = () => Date.now() }) {
  const buckets = new Map();

  const sweep = setInterval(() => {
    const t = now();
    for (const [key, b] of buckets) if (b.resetAt <= t) buckets.delete(key);
  }, windowMs);
  sweep.unref?.();

  function middleware(req, res, next) {
    const key = keyFn(req);
    const t = now();
    let bucket = buckets.get(key);
    if (!bucket || bucket.resetAt <= t) {
      bucket = { count: 0, resetAt: t + windowMs };
      buckets.set(key, bucket);
    }
    bucket.count += 1;
    res.setHeader('RateLimit-Limit', String(max));
    res.setHeader('RateLimit-Remaining', String(Math.max(0, max - bucket.count)));
    if (bucket.count > max) {
      const retryAfter = Math.ceil((bucket.resetAt - t) / 1000);
      res.setHeader('Retry-After', String(retryAfter));
      return res.status(429).json({ ok: false, code: 'RATE_LIMITED', message: 'Too many requests' });
    }
    next();
  }

  middleware.close = () => clearInterval(sweep);
  return middleware;
}
