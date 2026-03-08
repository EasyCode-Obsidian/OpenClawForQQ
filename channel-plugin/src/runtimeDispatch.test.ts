import { describe, expect, it, vi } from "vitest";

import { __testing } from "./index.js";

describe("qqdm runtime dispatch", () => {
  it("dispatches inbound using ctx.runtime.channel APIs", async () => {
    const sendText = vi.fn().mockResolvedValue({ commandId: "cmd-1", status: "delivered" });
    const recordInboundSession = vi.fn().mockResolvedValue(undefined);
    const dispatchReplyWithBufferedBlockDispatcher = vi.fn().mockImplementation(async ({ dispatcherOptions }) => {
      await dispatcherOptions.deliver({ text: "reply from agent" });
    });

    const runtimeCtx = {
      cfg: { session: { store: null } },
      accountId: "qqbot:123456789",
      account: { accountId: "qqbot:123456789" },
      runtime: {
        channel: {
          routing: {
            resolveAgentRoute: vi.fn().mockReturnValue({
              agentId: "main",
              accountId: "qqbot:123456789",
              sessionKey: "qqdm:qqbot:123456789:2315611636",
            }),
          },
          session: {
            resolveStorePath: vi.fn().mockReturnValue("store-path"),
            readSessionUpdatedAt: vi.fn().mockReturnValue(null),
            recordInboundSession,
          },
          reply: {
            resolveEnvelopeFormatOptions: vi.fn().mockReturnValue({}),
            formatAgentEnvelope: vi.fn().mockReturnValue("hello body"),
            finalizeInboundContext: vi.fn().mockReturnValue({
              SessionKey: "qqdm:qqbot:123456789:2315611636",
            }),
            dispatchReplyWithBufferedBlockDispatcher,
          },
        },
      },
      log: { warn: vi.fn(), error: vi.fn() },
    } as any;

    await __testing.dispatchInbound(
      runtimeCtx,
      {
        accountId: "qqbot:123456789",
        userId: "2315611636",
        sessionKey: "qqdm:qqbot:123456789:2315611636",
        text: "你好",
        messageId: "msg-1",
        eventId: "evt-1",
        timestamp: 1_772_950_000_000,
      },
      { sendText } as any,
    );

    expect(recordInboundSession).toHaveBeenCalledTimes(1);
    expect(dispatchReplyWithBufferedBlockDispatcher).toHaveBeenCalledTimes(1);
    expect(sendText).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: "qqbot:123456789",
        userId: "2315611636",
        text: "reply from agent",
      }),
    );
  });

  it("dispatches inbound using channelRuntime APIs when ctx.runtime only exposes process hooks", async () => {
    const sendText = vi.fn().mockResolvedValue({ commandId: "cmd-2", status: "delivered" });
    const recordInboundSession = vi.fn().mockResolvedValue(undefined);
    const dispatchReplyWithBufferedBlockDispatcher = vi.fn().mockImplementation(async ({ dispatcherOptions }) => {
      await dispatcherOptions.deliver({ text: "reply from channelRuntime" });
    });

    const runtimeCtx = {
      cfg: { session: { store: null } },
      accountId: "qqbot:123456789",
      account: { accountId: "qqbot:123456789" },
      runtime: {
        log: vi.fn(),
        error: vi.fn(),
        exit: vi.fn(),
      },
      channelRuntime: {
        routing: {
          resolveAgentRoute: vi.fn().mockReturnValue({
            agentId: "main",
            accountId: "qqbot:123456789",
            sessionKey: "qqdm:qqbot:123456789:2315611636",
          }),
        },
        session: {
          resolveStorePath: vi.fn().mockReturnValue("store-path"),
          readSessionUpdatedAt: vi.fn().mockReturnValue(null),
          recordInboundSession,
        },
        reply: {
          resolveEnvelopeFormatOptions: vi.fn().mockReturnValue({}),
          formatAgentEnvelope: vi.fn().mockReturnValue("hello body"),
          finalizeInboundContext: vi.fn().mockReturnValue({
            SessionKey: "qqdm:qqbot:123456789:2315611636",
          }),
          dispatchReplyWithBufferedBlockDispatcher,
        },
      },
      log: { warn: vi.fn(), error: vi.fn() },
    } as any;

    await __testing.dispatchInbound(
      runtimeCtx,
      {
        accountId: "qqbot:123456789",
        userId: "2315611636",
        sessionKey: "qqdm:qqbot:123456789:2315611636",
        text: "你好",
        messageId: "msg-2",
        eventId: "evt-2",
        timestamp: 1_772_950_000_001,
      },
      { sendText } as any,
    );

    expect(recordInboundSession).toHaveBeenCalledTimes(1);
    expect(dispatchReplyWithBufferedBlockDispatcher).toHaveBeenCalledTimes(1);
    expect(sendText).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: "qqbot:123456789",
        userId: "2315611636",
        text: "reply from channelRuntime",
      }),
    );
  });
});
