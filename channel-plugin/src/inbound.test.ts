import { describe, expect, it } from "vitest";

import { createPlugin } from "./index.js";
import { messageTypes, type InboundMessage } from "./protocol.js";

describe("qqdm inbound delivery", () => {
  it("translates an inbound.message frame into OpenClaw channel input", () => {
    const plugin = createPlugin({ sessionPrefix: "qqdm" });
    const frame: InboundMessage = {
      type: messageTypes.inboundMessage,
      id: "evt_20260308_001",
      timestamp: 1_772_900_001_000,
      traceId: "trace_qqdm_001",
      accountId: "qqbot:123456789",
      peer: {
        type: "dm",
        userId: "987654321",
      },
      message: {
        id: "qqmsg_abc_001",
        text: "你好，龙虾",
      },
      source: {
        provider: "overflow",
        eventType: "private_message",
      },
    };

    const result = plugin.receiveInbound(frame);

    expect(result).toEqual({
      delivered: true,
      duplicate: false,
      input: {
        accountId: "qqbot:123456789",
        userId: "987654321",
        sessionKey: "qqdm:qqbot:123456789:987654321",
        text: "你好，龙虾",
        messageId: "qqmsg_abc_001",
        eventId: "evt_20260308_001",
        timestamp: 1_772_900_001_000,
        traceId: "trace_qqdm_001",
      },
    });
  });

  it("does not redeliver duplicate inbound.message frames", () => {
    const plugin = createPlugin({ sessionPrefix: "qqdm" });
    const frame: InboundMessage = {
      type: messageTypes.inboundMessage,
      id: "evt_20260308_002",
      timestamp: 1_772_900_002_000,
      accountId: "qqbot:123456789",
      peer: {
        type: "dm",
        userId: "987654321",
      },
      message: {
        id: "qqmsg_abc_002",
        text: "再来一条",
      },
    };

    const first = plugin.receiveInbound(frame);
    const duplicate = plugin.receiveInbound(frame);

    expect(first.delivered).toBe(true);
    expect(duplicate).toEqual({
      delivered: false,
      duplicate: true,
      dedupKey: "qqbot:123456789:evt_20260308_002:qqmsg_abc_002",
    });
  });
});
