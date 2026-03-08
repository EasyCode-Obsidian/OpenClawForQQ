import { describe, expect, it } from "vitest";

import { SessionMap } from "./sessionMap.js";

describe("SessionMap", () => {
  it("maps the same account and user to the same deterministic session key", () => {
    const map = new SessionMap("qqdm");

    expect(map.toSessionKey("qqbot:123456789", "987654321")).toBe(
      "qqdm:qqbot:123456789:987654321",
    );
    expect(map.toSessionKey("qqbot:123456789", "987654321")).toBe(
      "qqdm:qqbot:123456789:987654321",
    );
  });

  it("uses different keys when the account or user changes", () => {
    const map = new SessionMap("qqdm");

    expect(map.toSessionKey("qqbot:123456789", "987654321")).not.toBe(
      map.toSessionKey("qqbot:111111111", "987654321"),
    );
    expect(map.toSessionKey("qqbot:123456789", "987654321")).not.toBe(
      map.toSessionKey("qqbot:123456789", "555555555"),
    );
  });

  it("deduplicates repeated inbound events within the ttl window", () => {
    const map = new SessionMap("qqdm", 10_000);
    const dedupKey = map.toDedupKey("qqbot:123456789", "evt_1", "msg_1");

    expect(map.markInboundSeen(dedupKey, 1_000)).toBe(true);
    expect(map.markInboundSeen(dedupKey, 5_000)).toBe(false);
    expect(map.markInboundSeen(dedupKey, 12_000)).toBe(true);
  });
});
