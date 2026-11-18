/**
 * Remembers requestIds that already produced a verified response so a captured
 * (body, token) pair cannot be replayed inside the token freshness window.
 *
 * TODO(prod): use Redis `SET key 1 NX PX ttl` so this works across instances.
 */
export class InMemoryReplayStore {
  constructor({ ttlMs, now = () => Date.now() }) {
    this.ttlMs = ttlMs;
    this.now = now;
    this.seen = new Map();
    this.sweep = setInterval(() => this.#evict(), Math.max(1000, ttlMs));
    this.sweep.unref?.();
  }

  has(id) {
    const exp = this.seen.get(id);
    if (exp === undefined) return false;
    if (exp <= this.now()) {
      this.seen.delete(id);
      return false;
    }
    return true;
  }

  /** Returns false if the id was already used (atomic check-and-set). */
  markUsed(id) {
    if (this.has(id)) return false;
    this.seen.set(id, this.now() + this.ttlMs);
    return true;
  }

  close() {
    clearInterval(this.sweep);
  }

  #evict() {
    const t = this.now();
    for (const [id, exp] of this.seen) if (exp <= t) this.seen.delete(id);
  }
}
