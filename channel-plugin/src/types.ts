import type { QqdmConfig, ResolvedQqdmAccount } from "./config.js";
import type { InboundMessage } from "./protocol.js";
import type { SessionMap } from "./sessionMap.js";
import type { WsBridgeServer } from "./wsServer.js";

export type OpenClawChannelInput = {
  accountId: string;
  userId: string;
  sessionKey: string;
  text: string;
  messageId: string;
  eventId: string;
  timestamp: number;
  traceId?: string;
};

export type InboundDeliveryResult =
  | {
      delivered: true;
      duplicate: false;
      input: OpenClawChannelInput;
    }
  | {
      delivered: false;
      duplicate: true;
      dedupKey: string;
    };

export type OutboundSendRequest = {
  accountId: string;
  userId: string;
  text: string;
  traceId?: string;
};

export type OutboundSendResult = {
  commandId: string;
  status: "accepted" | "delivered";
};

export type OutboundSendError = {
  commandId: string;
  code: string;
  message: string;
};

export type RegisteredChannelPlugin = {
  id: "qqdm";
  meta: {
    id: "qqdm";
    label: string;
    selectionLabel: string;
    docsPath: string;
    blurb: string;
    aliases: string[];
  };
  capabilities: {
    chatTypes: ["direct"];
    media: false;
    threads: false;
    nativeCommands: false;
    blockStreaming: true;
  };
  configSchema: {
    schema: Record<string, unknown>;
    uiHints?: Record<string, unknown>;
  };
  config: {
    listAccountIds(cfg: Record<string, any>): string[];
    resolveAccount(cfg: Record<string, any>, accountId?: string | null): ResolvedQqdmAccount;
    defaultAccountId?(cfg: Record<string, any>): string;
    isConfigured?(account: ResolvedQqdmAccount): boolean;
    isEnabled?(account: ResolvedQqdmAccount): boolean;
    describeAccount?(account: ResolvedQqdmAccount): Record<string, unknown>;
  };
  outbound: {
    deliveryMode: "direct";
    sendText(ctx: Record<string, any>): Promise<Record<string, unknown>>;
  };
  gateway: {
    startAccount(ctx: Record<string, any>): Promise<void>;
    stopAccount(ctx: Record<string, any>): Promise<void>;
  };
  wsServer: WsBridgeServer;
  sessionMap: SessionMap;
  receiveInbound(message: InboundMessage): InboundDeliveryResult;
  sendText(request: OutboundSendRequest): Promise<OutboundSendResult>;
};

export type OpenClawApi = {
  config?: Record<string, any>;
  logger?: {
    info?(message: string): void;
    warn?(message: string): void;
    error?(message: string): void;
  };
  registerChannel(registration: { plugin: RegisteredChannelPlugin } | RegisteredChannelPlugin): void | Promise<void>;
  registerService?(service: { id: string; start(ctx: Record<string, any>): void | Promise<void>; stop?(ctx: Record<string, any>): void | Promise<void> }): void | Promise<void>;
};
