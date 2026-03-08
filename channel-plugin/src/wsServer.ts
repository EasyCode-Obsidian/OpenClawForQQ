import type { AddressInfo } from "node:net";

import WebSocket, { WebSocketServer } from "ws";

import { createHelloAck, listConfiguredAccountIds, type QqdmConfig } from "./config.js";
import {
  messageTypes,
  type AckMessage,
  type ErrorMessage,
  type HelloMessage,
  type InboundMessage,
  type OutboundSendTextMessage,
} from "./protocol.js";
import type { OutboundSendError, OutboundSendRequest, OutboundSendResult } from "./types.js";

type Frame = Record<string, unknown> & { type?: string; id?: string; replyTo?: string };

type PendingCommand = {
  resolve: (value: OutboundSendResult) => void;
  reject: (reason: OutboundSendError) => void;
};

export type AccountStateChange = {
  accountId: string;
  connected: boolean;
};

export class WsBridgeServer {
  url = "";

  private readonly socketAccounts = new Map<WebSocket, string[]>();
  private readonly onlineAccounts = new Set<string>();
  private readonly accountSockets = new Map<string, WebSocket>();
  private readonly pendingCommands = new Map<string, PendingCommand>();
  private commandCounter = 0;
  private server?: WebSocketServer;
  private inboundHandler?: (message: InboundMessage) => Promise<void> | void;
  private accountStateHandler?: (state: AccountStateChange) => void;

  constructor(private readonly config: QqdmConfig) {
    this.url = `ws://${config.bindHost}:${config.bindPort}/ws`;
  }

  setInboundHandler(handler: (message: InboundMessage) => Promise<void> | void): void {
    this.inboundHandler = handler;
  }

  setAccountStateHandler(handler: (state: AccountStateChange) => void): void {
    this.accountStateHandler = handler;
  }

  async start(): Promise<void> {
    if (this.server) {
      return;
    }

    this.server = new WebSocketServer({
      host: this.config.bindHost,
      port: this.config.bindPort,
      path: "/ws",
    });

    await new Promise<void>((resolve, reject) => {
      this.server?.once("listening", () => resolve());
      this.server?.once("error", (error: Error) => reject(error));
    });

    const address = this.server.address();
    if (address && typeof address !== "string") {
      const info = address as AddressInfo;
      this.url = `ws://${this.config.bindHost}:${info.port}/ws`;
    }

    this.server.on("connection", (socket: WebSocket) => {
      let isAuthenticated = false;

      socket.on("message", async (raw: WebSocket.RawData) => {
        const frame = this.parseFrame(raw);
        if (!frame) {
          this.sendError(socket, "invalid_json", "Received invalid JSON frame.");
          return;
        }

        if (!isAuthenticated) {
          const result = this.handleHello(socket, frame);
          isAuthenticated = result;
          return;
        }

        if (frame.type === messageTypes.inboundMessage) {
          await this.inboundHandler?.(frame as InboundMessage);
          return;
        }

        if (frame.type === messageTypes.ping && typeof frame.id === "string") {
          const ack: AckMessage = {
            type: messageTypes.ack,
            id: `ack_${frame.id}`,
            replyTo: frame.id,
            timestamp: Date.now(),
            status: "pong",
          };
          socket.send(JSON.stringify(ack));
          return;
        }

        if (frame.type === messageTypes.ack && typeof frame.replyTo === "string") {
          const pending = this.pendingCommands.get(frame.replyTo);
          if (pending) {
            this.pendingCommands.delete(frame.replyTo);
            const ack = frame as AckMessage;
            pending.resolve({
              commandId: frame.replyTo,
              status: ack.status === "accepted" ? "accepted" : "delivered",
            });
          }
          return;
        }

        if (frame.type === messageTypes.error && typeof frame.replyTo === "string") {
          const pending = this.pendingCommands.get(frame.replyTo);
          if (pending) {
            this.pendingCommands.delete(frame.replyTo);
            const error = frame as ErrorMessage;
            pending.reject({
              commandId: frame.replyTo,
              code: error.code,
              message: error.message,
            });
          }
        }
      });

      socket.on("close", () => {
        const accounts = this.socketAccounts.get(socket) ?? [];
        for (const accountId of accounts) {
          if (this.accountSockets.get(accountId) === socket) {
            this.accountSockets.delete(accountId);
          }
          if (!this.findOpenSocketForAccount(accountId)) {
            this.onlineAccounts.delete(accountId);
            this.accountStateHandler?.({ accountId, connected: false });
          }
        }
        this.socketAccounts.delete(socket);
      });
    });
  }

  async stop(): Promise<void> {
    if (!this.server) {
      return;
    }

    const server = this.server;
    this.server = undefined;
    this.socketAccounts.clear();
    this.onlineAccounts.clear();
    this.accountSockets.clear();
    this.pendingCommands.clear();

    await new Promise<void>((resolve, reject) => {
      server.close((error?: Error) => {
        if (error) {
          reject(error);
          return;
        }
        resolve();
      });
    });
  }

  isAccountOnline(accountId: string): boolean {
    return this.onlineAccounts.has(accountId);
  }

  async sendText(request: OutboundSendRequest): Promise<OutboundSendResult> {
    const socket = this.accountSockets.get(request.accountId);
    if (!socket || socket.readyState !== WebSocket.OPEN) {
      throw {
        commandId: "",
        code: "connector_unavailable",
        message: `No active connector for account ${request.accountId}.`,
      } satisfies OutboundSendError;
    }

    const commandId = this.nextCommandId();
    const frame: OutboundSendTextMessage = {
      type: messageTypes.outboundSendText,
      id: commandId,
      timestamp: Date.now(),
      accountId: request.accountId,
      peer: {
        type: "dm",
        userId: request.userId,
      },
      text: request.text,
      ...(request.traceId ? { traceId: request.traceId } : {}),
    };

    const pending = new Promise<OutboundSendResult>((resolve, reject) => {
      this.pendingCommands.set(commandId, { resolve, reject });
    });

    socket.send(JSON.stringify(frame));
    return await pending;
  }

  private nextCommandId(): string {
    this.commandCounter += 1;
    return `send_${Date.now()}_${this.commandCounter}`;
  }

  private parseFrame(raw: WebSocket.RawData): Frame | null {
    try {
      const parsed = JSON.parse(String(raw));
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        return null;
      }
      return parsed as Frame;
    } catch {
      return null;
    }
  }

  private handleHello(socket: WebSocket, frame: Frame): boolean {
    if (frame.type !== messageTypes.hello || typeof frame.id !== "string") {
      this.sendError(socket, "invalid_hello", "Expected hello frame as the first authenticated message.", frame.id);
      socket.close(1008, "invalid hello");
      return false;
    }

    const hello = frame as HelloMessage;
    if (hello.auth?.scheme !== "shared_secret" || hello.auth?.token !== this.config.sharedSecret) {
      this.sendError(socket, "auth_failed", "Shared secret authentication failed.", hello.id);
      socket.close(1008, "auth failed");
      return false;
    }

    const acceptedAccounts = this.filterAccounts(hello.accounts ?? []);
    this.socketAccounts.set(socket, acceptedAccounts);
    for (const accountId of acceptedAccounts) {
      this.onlineAccounts.add(accountId);
      const activeSocket = this.accountSockets.get(accountId);
      if (!activeSocket || activeSocket.readyState !== WebSocket.OPEN) {
        this.accountSockets.set(accountId, socket);
      }
      this.accountStateHandler?.({ accountId, connected: true });
    }

    socket.send(JSON.stringify(createHelloAck(this.config, hello.id, acceptedAccounts)));
    return true;
  }

  private findOpenSocketForAccount(accountId: string): WebSocket | undefined {
    for (const [socket, accounts] of this.socketAccounts.entries()) {
      if (accounts.includes(accountId) && socket.readyState === WebSocket.OPEN) {
        return socket;
      }
    }
    return undefined;
  }

  private filterAccounts(accounts: unknown): string[] {
    const offered = Array.isArray(accounts)
      ? accounts.filter((value): value is string => typeof value === "string")
      : typeof accounts === "object" && accounts !== null
        ? Object.keys(accounts as Record<string, unknown>)
        : [];
    const configured = listConfiguredAccountIds(this.config);
    if (configured.length === 0) {
      return [...offered];
    }

    const allowed = new Set(configured);
    return offered.filter((accountId) => allowed.has(accountId));
  }

  private sendError(socket: WebSocket, code: string, message: string, replyTo?: unknown): void {
    const errorFrame: ErrorMessage = {
      type: messageTypes.error,
      id: `error_${Date.now()}`,
      timestamp: Date.now(),
      code,
      message,
      ...(typeof replyTo === "string" ? { replyTo } : {}),
    };
    socket.send(JSON.stringify(errorFrame));
  }
}

