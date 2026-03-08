const DEFAULT_DEDUP_TTL_MS = 60_000;

type DedupEntry = {
  key: string;
  expiresAt: number;
};

export class SessionMap {
  private readonly dedup = new Map<string, DedupEntry>();

  constructor(
    private readonly prefix: string,
    private readonly dedupTtlMs: number = DEFAULT_DEDUP_TTL_MS,
  ) {}

  toSessionKey(accountId: string, userId: string): string {
    return `${this.prefix}:${accountId}:${userId}`;
  }

  toDedupKey(accountId: string, eventId: string, messageId: string): string {
    return `${accountId}:${eventId}:${messageId}`;
  }

  markInboundSeen(dedupKey: string, now: number = Date.now()): boolean {
    this.pruneExpired(now);

    if (this.dedup.has(dedupKey)) {
      return false;
    }

    this.dedup.set(dedupKey, {
      key: dedupKey,
      expiresAt: now + this.dedupTtlMs,
    });
    return true;
  }

  private pruneExpired(now: number): void {
    for (const [key, entry] of this.dedup.entries()) {
      if (entry.expiresAt <= now) {
        this.dedup.delete(key);
      }
    }
  }
}
