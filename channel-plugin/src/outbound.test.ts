import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { RawData } from "ws";

import { createPlugin } from "./index.js";
import { messageTypes } from "./protocol.js";
import type { ErrorMessage } from "./protocol.js";

async function waitForOpen(socket: WebSocket): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    socket.once("open", () => resolve());
    socket.once("error", reject);
  });
}

async function waitForJson(socket: WebSocket): Promise<unknown> {
  return await new Promise((resolve, reject) => {
    socket.once("message", (data: RawData) => {
      try {
        resolve(JSON.parse(String(data)));
      } catch (error) {
        reject(error);
      }
    });
    socket.once("error", reject);
  });
}

describe("qqdm outbound delivery", () => {
  const sockets: WebSocket[] = [];
  const stops: Array<() => Promise<void>> = [];

  afterEach(async () => {
    while (sockets.length > 0) {
      sockets.pop()?.close();
    }
    while (stops.length > 0) {
      await stops.pop()?.();
    }
  });

  it("emits outbound.send_text over the active connector session", async () => {
    const plugin = createPlugin({
      bindHost: "127.0.0.1",
      bindPort: 0,
      sharedSecret: "secret-123",
      accounts: { "qqbot:123456789": { enabled: true } },
    });
    await plugin.wsServer.start();
    stops.push(() => plugin.wsServer.stop());

    const socket = new WebSocket(plugin.wsServer.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_out_1",
        timestamp: Date.now(),
        connector: {
          instanceId: "connector-01",
          version: "0.1.0",
          provider: "overflow",
        },
        auth: {
          scheme: "shared_secret",
          token: "secret-123",
        },
        accounts: ["qqbot:123456789"],
        capabilities: ["dm_text_out", "ack"],
      }),
    );
    await waitForJson(socket);

    const pending = plugin.sendText({
      accountId: "qqbot:123456789",
      userId: "987654321",
      text: "你好，我在。",
      traceId: "trace_qqdm_001",
    });

    const outbound = await waitForJson(socket);
    expect(outbound).toEqual(
      expect.objectContaining({
        type: messageTypes.outboundSendText,
        accountId: "qqbot:123456789",
        peer: {
          type: "dm",
          userId: "987654321",
        },
        text: "你好，我在。",
        traceId: "trace_qqdm_001",
      }),
    );

    const commandId = (outbound as { id: string }).id;
    socket.send(
      JSON.stringify({
        type: messageTypes.ack,
        id: "ack_send_1",
        replyTo: commandId,
        timestamp: Date.now(),
        status: "delivered",
      }),
    );

    await expect(pending).resolves.toEqual(
      expect.objectContaining({
        commandId,
        status: "delivered",
      }),
    );
  });

  it("rejects outbound sends when the connector returns an error", async () => {
    const plugin = createPlugin({
      bindHost: "127.0.0.1",
      bindPort: 0,
      sharedSecret: "secret-123",
      accounts: { "qqbot:123456789": { enabled: true } },
    });
    await plugin.wsServer.start();
    stops.push(() => plugin.wsServer.stop());

    const socket = new WebSocket(plugin.wsServer.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_out_2",
        timestamp: Date.now(),
        connector: {
          instanceId: "connector-01",
          version: "0.1.0",
          provider: "overflow",
        },
        auth: {
          scheme: "shared_secret",
          token: "secret-123",
        },
        accounts: ["qqbot:123456789"],
        capabilities: ["dm_text_out", "ack"],
      }),
    );
    await waitForJson(socket);

    const pending = plugin.sendText({
      accountId: "qqbot:123456789",
      userId: "987654321",
      text: "发送失败测试",
    });

    const outbound = (await waitForJson(socket)) as { id: string };
    const errorFrame: ErrorMessage = {
      type: messageTypes.error,
      id: "error_send_1",
      replyTo: outbound.id,
      timestamp: Date.now(),
      code: "qq_send_failed",
      message: "send failed",
    };
    socket.send(JSON.stringify(errorFrame));

    await expect(pending).rejects.toMatchObject({
      commandId: outbound.id,
      code: "qq_send_failed",
      message: "send failed",
    });
  });
});



