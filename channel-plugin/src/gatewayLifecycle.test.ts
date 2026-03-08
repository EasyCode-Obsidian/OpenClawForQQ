import { describe, expect, it } from "vitest";

import { createPlugin } from "./index.js";

describe("qqdm gateway lifecycle", () => {
  it("keeps startAccount running until abort is signaled", async () => {
    const plugin = createPlugin({
      bindHost: "127.0.0.1",
      bindPort: 19191,
      sharedSecret: "test-secret",
      accounts: {
        "qqbot:123456789": { accountId: "qqbot:123456789", enabled: true },
      },
    });

    const abortController = new AbortController();
    let resolved = false;
    const startPromise = plugin.gateway.startAccount({
      accountId: "qqbot:123456789",
      cfg: {},
      account: { accountId: "qqbot:123456789" },
      getStatus: () => ({}),
      setStatus: () => undefined,
      abortSignal: abortController.signal,
    } as any).then(() => {
      resolved = true;
    });

    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(resolved).toBe(false);

    abortController.abort();
    await startPromise;
    expect(resolved).toBe(true);

    await plugin.wsServer.stop();
  });
});
