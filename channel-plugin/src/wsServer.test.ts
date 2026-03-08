import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import type { RawData } from "ws";

import { resolveConfig } from "./config.js";
import { messageTypes } from "./protocol.js";
import { WsBridgeServer } from "./wsServer.js";

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

describe("WsBridgeServer", () => {
  const servers: WsBridgeServer[] = [];
  const sockets: WebSocket[] = [];

  afterEach(async () => {
    while (sockets.length > 0) {
      const socket = sockets.pop();
      socket?.close();
    }

    while (servers.length > 0) {
      const server = servers.pop();
      await server?.stop();
    }
  });

  it("accepts a valid hello and replies with hello_ack", async () => {
    const server = new WsBridgeServer(
      resolveConfig({
        bindHost: "127.0.0.1",
        bindPort: 0,
        sharedSecret: "secret-123",
        accounts: { "qqbot:123456789": { enabled: true } },
      }),
    );
    servers.push(server);
    await server.start();

    const socket = new WebSocket(server.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_1",
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
        capabilities: ["dm_text_in", "dm_text_out", "ack", "ping"],
      }),
    );

    await expect(waitForJson(socket)).resolves.toEqual(
      expect.objectContaining({
        type: messageTypes.helloAck,
        replyTo: "hello_1",
        accepted: true,
        accounts: ["qqbot:123456789"],
      }),
    );
  });

  it("rejects invalid shared secrets", async () => {
    const server = new WsBridgeServer(
      resolveConfig({
        bindHost: "127.0.0.1",
        bindPort: 0,
        sharedSecret: "secret-123",
      }),
    );
    servers.push(server);
    await server.start();

    const socket = new WebSocket(server.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_2",
        timestamp: Date.now(),
        connector: {
          instanceId: "connector-01",
          version: "0.1.0",
          provider: "overflow",
        },
        auth: {
          scheme: "shared_secret",
          token: "wrong-secret",
        },
        accounts: ["qqbot:123456789"],
        capabilities: ["dm_text_in"],
      }),
    );

    await expect(waitForJson(socket)).resolves.toEqual(
      expect.objectContaining({
        type: messageTypes.error,
        replyTo: "hello_2",
        code: "auth_failed",
      }),
    );
  });

  it("tracks accounts as online after hello_ack", async () => {
    const server = new WsBridgeServer(
      resolveConfig({
        bindHost: "127.0.0.1",
        bindPort: 0,
        sharedSecret: "secret-123",
        accounts: { "qqbot:123456789": { enabled: true } },
      }),
    );
    servers.push(server);
    await server.start();

    const socket = new WebSocket(server.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_3",
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
        accounts: { "qqbot:123456789": { enabled: true } },
        capabilities: ["dm_text_in"],
      }),
    );

    await waitForJson(socket);

    expect(server.isAccountOnline("qqbot:123456789")).toBe(true);
  });

  it("keeps the original connector mapped when a duplicate account socket disconnects", async () => {
    const server = new WsBridgeServer(
      resolveConfig({
        bindHost: "127.0.0.1",
        bindPort: 0,
        sharedSecret: "secret-123",
        accounts: { "qqbot:123456789": { enabled: true } },
      }),
    );
    servers.push(server);
    await server.start();

    const connectorSocket = new WebSocket(server.url);
    sockets.push(connectorSocket);
    await waitForOpen(connectorSocket);

    connectorSocket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_primary",
        timestamp: Date.now(),
        connector: {
          instanceId: "connector-primary",
          version: "0.1.0",
          provider: "overflow",
        },
        auth: {
          scheme: "shared_secret",
          token: "secret-123",
        },
        accounts: ["qqbot:123456789"],
        capabilities: ["dm_text_in", "dm_text_out", "ack", "ping"],
      }),
    );
    await waitForJson(connectorSocket);

    const duplicateSocket = new WebSocket(server.url);
    sockets.push(duplicateSocket);
    await waitForOpen(duplicateSocket);

    duplicateSocket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_duplicate",
        timestamp: Date.now(),
        connector: {
          instanceId: "connector-duplicate",
          version: "0.1.0",
          provider: "debug",
        },
        auth: {
          scheme: "shared_secret",
          token: "secret-123",
        },
        accounts: ["qqbot:123456789"],
        capabilities: ["dm_text_in", "dm_text_out", "ack", "ping"],
      }),
    );
    await waitForJson(duplicateSocket);

    duplicateSocket.close();
    await new Promise((resolve) => duplicateSocket.once("close", () => resolve(undefined)));

    const outboundPromise = waitForJson(connectorSocket);
    const sendPromise = server.sendText({
      accountId: "qqbot:123456789",
      userId: "2315611636",
      text: "hello after duplicate close",
    });

    await expect(outboundPromise).resolves.toEqual(
      expect.objectContaining({
        type: messageTypes.outboundSendText,
        accountId: "qqbot:123456789",
        text: "hello after duplicate close",
      }),
    );

    const outbound = await outboundPromise as Record<string, unknown>;
    connectorSocket.send(
      JSON.stringify({
        type: messageTypes.ack,
        id: `ack_${String(outbound.id)}`,
        replyTo: String(outbound.id),
        timestamp: Date.now(),
        status: "delivered",
      }),
    );

    await expect(sendPromise).resolves.toEqual(
      expect.objectContaining({
        status: "delivered",
      }),
    );
  });

  it("answers ping with ack pong", async () => {
    const server = new WsBridgeServer(
      resolveConfig({
        bindHost: "127.0.0.1",
        bindPort: 0,
        sharedSecret: "secret-123",
      }),
    );
    servers.push(server);
    await server.start();

    const socket = new WebSocket(server.url);
    sockets.push(socket);
    await waitForOpen(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.hello,
        id: "hello_4",
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
        accounts: [],
        capabilities: ["ping"],
      }),
    );
    await waitForJson(socket);

    socket.send(
      JSON.stringify({
        type: messageTypes.ping,
        id: "ping_1",
        timestamp: Date.now(),
      }),
    );

    await expect(waitForJson(socket)).resolves.toEqual(
      expect.objectContaining({
        type: messageTypes.ack,
        replyTo: "ping_1",
        status: "pong",
      }),
    );
  });
});



