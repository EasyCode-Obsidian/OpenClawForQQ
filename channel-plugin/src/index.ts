import {
  listConfiguredAccountIds,
  qqdmConfigSchema,
  resolveAccountConfig,
  resolveConfig,
  type QqdmConfig,
} from "./config.js";
import type { InboundMessage } from "./protocol.js";
import { protocolVersion } from "./protocol.js";
import { SessionMap } from "./sessionMap.js";
import type {
  InboundDeliveryResult,
  OpenClawApi,
  OpenClawChannelInput,
  OutboundSendRequest,
  OutboundSendResult,
  RegisteredChannelPlugin,
} from "./types.js";
import { WsBridgeServer } from "./wsServer.js";

type RuntimeAccountContext = {
  cfg: Record<string, any>;
  accountId: string;
  account: Record<string, any>;
  runtime?: Record<string, any>;
  channelRuntime?: Record<string, any>;
  getStatus?: () => Record<string, any>;
  setStatus?: (next: Record<string, any>) => void;
  log?: {
    info?(message: string): void;
    warn?(message: string): void;
    error?(message: string): void;
  };
};

function getChannelSection(cfg: Record<string, any> | undefined): Partial<QqdmConfig> {
  return (cfg?.channels?.qqdm ?? {}) as Partial<QqdmConfig>;
}

function parseDirectTarget(target: string): string {
  const trimmed = target.trim();
  if (!trimmed.includes(":")) {
    return trimmed;
  }
  const parts = trimmed.split(":");
  return parts[parts.length - 1] || trimmed;
}

async function dispatchInbound(
  runtimeCtx: RuntimeAccountContext,
  message: OpenClawChannelInput,
  plugin: RegisteredChannelPlugin,
): Promise<void> {
  const core = runtimeCtx.channelRuntime ?? runtimeCtx.runtime?.channel ?? runtimeCtx.runtime;
  if (!core?.routing || !core?.reply || !core?.session) {
    runtimeCtx.log?.warn?.(`qqdm: channel runtime unavailable for account ${runtimeCtx.accountId}; dropping inbound message`);
    return;
  }

  const route = core.routing.resolveAgentRoute({
    cfg: runtimeCtx.cfg,
    channel: "qqdm",
    accountId: message.accountId,
    peer: {
      kind: "direct",
      id: message.userId,
    },
  });

  const storePath = core.session.resolveStorePath(runtimeCtx.cfg?.session?.store, {
    agentId: route.agentId,
  });
  const previousTimestamp = core.session.readSessionUpdatedAt({
    storePath,
    sessionKey: route.sessionKey,
  });
  const envelope = core.reply.resolveEnvelopeFormatOptions(runtimeCtx.cfg);
  const body = core.reply.formatAgentEnvelope({
    channel: "QQ DM",
    from: `qq:${message.userId}`,
    timestamp: message.timestamp,
    previousTimestamp,
    envelope,
    body: message.text,
  });

  const inboundContext = core.reply.finalizeInboundContext({
    Body: body,
    BodyForAgent: message.text,
    RawBody: message.text,
    CommandBody: message.text,
    From: `qqdm:${message.userId}`,
    To: `qqdm:${message.userId}`,
    SessionKey: route.sessionKey,
    AccountId: route.accountId,
    ChatType: "direct",
    ConversationLabel: `qq:${message.userId}`,
    SenderId: message.userId,
    Provider: "qqdm",
    Surface: "qqdm",
    MessageSid: message.messageId,
    Timestamp: message.timestamp,
    OriginatingChannel: "qqdm",
    OriginatingTo: `qqdm:${message.userId}`,
    CommandAuthorized: true,
  });

  await core.session.recordInboundSession({
    storePath,
    sessionKey: inboundContext.SessionKey ?? route.sessionKey,
    ctx: inboundContext,
    onRecordError: (error: unknown) => {
      runtimeCtx.log?.warn?.(`qqdm: failed recording session metadata: ${String(error)}`);
    },
  });

  await core.reply.dispatchReplyWithBufferedBlockDispatcher({
    ctx: inboundContext,
    cfg: runtimeCtx.cfg,
    dispatcherOptions: {
      deliver: async (payload: Record<string, unknown>) => {
        const text = typeof payload.text === "string" ? payload.text : "";
        if (!text.trim()) {
          return;
        }
        await plugin.sendText({
          accountId: message.accountId,
          userId: message.userId,
          text,
          traceId: message.traceId,
        });
      },
      onError: (error: unknown, info: { kind: string }) => {
        runtimeCtx.log?.error?.(`qqdm: ${info.kind} reply failed: ${String(error)}`);
      },
    },
  });
}

export function createPlugin(config?: Partial<QqdmConfig>): RegisteredChannelPlugin {
  const resolved = resolveConfig(config);
  const sessionMap = new SessionMap(resolved.sessionPrefix);
  const wsServer = new WsBridgeServer(resolved);
  const runtimeAccounts = new Map<string, RuntimeAccountContext>();

  const plugin: RegisteredChannelPlugin = {
    id: "qqdm",
    meta: {
      id: "qqdm",
      label: "QQ DM",
      selectionLabel: "QQ DM (WebSocket Bridge)",
      docsPath: "/channels/qqdm",
      blurb: "QQ direct-message bridge over WebSocket.",
      aliases: ["qq"],
    },
    capabilities: {
      chatTypes: ["direct"],
      media: false,
      threads: false,
      nativeCommands: false,
      blockStreaming: true,
    },
    configSchema: qqdmConfigSchema,
    config: {
      listAccountIds: (cfg) => listConfiguredAccountIds(resolveConfig(getChannelSection(cfg))),
      resolveAccount: (cfg, accountId) => resolveAccountConfig(resolveConfig(getChannelSection(cfg)), accountId),
      defaultAccountId: (cfg) => listConfiguredAccountIds(resolveConfig(getChannelSection(cfg)))[0] ?? "default",
      isConfigured: (account) => Boolean(account.accountId?.trim()),
      isEnabled: (account) => account.enabled !== false,
      describeAccount: (account) => ({
        accountId: account.accountId,
        enabled: account.enabled,
        configured: Boolean(account.accountId?.trim()),
        connected: wsServer.isAccountOnline(account.accountId),
      }),
    },
    outbound: {
      deliveryMode: "direct",
      sendText: async (ctx) => {
        const accountId =
          typeof ctx.accountId === "string" && ctx.accountId.trim()
            ? ctx.accountId
            : listConfiguredAccountIds(resolved)[0] ?? "default";
        const userId = parseDirectTarget(String(ctx.to ?? ""));
        const result = await wsServer.sendText({
          accountId,
          userId,
          text: String(ctx.text ?? ""),
        });
        return {
          channel: "qqdm",
          messageId: result.commandId,
          chatId: userId,
          meta: { status: result.status },
        };
      },
    },
    gateway: {
      startAccount: async (ctx: any) => {
        runtimeAccounts.set(ctx.accountId, ctx as RuntimeAccountContext);
        ctx.log?.info?.(`qqdm: startAccount ${ctx.accountId}`);
        await wsServer.start();
        ctx.setStatus?.({
          ...ctx.getStatus?.(),
          accountId: ctx.accountId,
          enabled: true,
          configured: true,
          running: true,
          connected: wsServer.isAccountOnline(ctx.accountId),
        });

        await new Promise<void>((resolve) => {
          if (ctx.abortSignal?.aborted) {
            resolve();
            return;
          }
          ctx.abortSignal?.addEventListener("abort", () => resolve(), { once: true });
        });
      },
      stopAccount: async (ctx: any) => {
        runtimeAccounts.delete(ctx.accountId);
        if (runtimeAccounts.size === 0) {
          await wsServer.stop();
        }
        ctx.setStatus?.({
          ...ctx.getStatus?.(),
          accountId: ctx.accountId,
          running: false,
          connected: false,
        });
      },
    },
    wsServer,
    sessionMap,
    receiveInbound(message: InboundMessage): InboundDeliveryResult {
      const dedupKey = sessionMap.toDedupKey(message.accountId, message.id, message.message.id);
      if (!sessionMap.markInboundSeen(dedupKey, message.timestamp)) {
        return {
          delivered: false,
          duplicate: true,
          dedupKey,
        };
      }

      return {
        delivered: true,
        duplicate: false,
        input: {
          accountId: message.accountId,
          userId: message.peer.userId,
          sessionKey: sessionMap.toSessionKey(message.accountId, message.peer.userId),
          text: message.message.text,
          messageId: message.message.id,
          eventId: message.id,
          timestamp: message.timestamp,
          ...(message.traceId ? { traceId: message.traceId } : {}),
        },
      };
    },
    async sendText(request: OutboundSendRequest): Promise<OutboundSendResult> {
      return await wsServer.sendText(request);
    },
  };

  wsServer.setInboundHandler(async (message) => {
    const result = plugin.receiveInbound(message);
    if (!result.delivered) {
      return;
    }
    const runtimeCtx = runtimeAccounts.get(result.input.accountId);
    if (!runtimeCtx) {
      return;
    }
    await dispatchInbound(runtimeCtx, result.input, plugin);
  });

  wsServer.setAccountStateHandler((state) => {
    const runtimeCtx = runtimeAccounts.get(state.accountId);
    if (!runtimeCtx) {
      return;
    }
    runtimeCtx.setStatus?.({
      ...runtimeCtx.getStatus?.(),
      accountId: state.accountId,
      connected: state.connected,
      running: true,
      configured: true,
      enabled: true,
    });
  });

  return plugin;
}

export function activate(api: OpenClawApi, config?: Partial<QqdmConfig>) {
  const effectiveConfig = resolveConfig(config ?? getChannelSection(api.config));
  const plugin = createPlugin(effectiveConfig);

  if (api.registerService) {
    api.registerService({
      id: "qqdm-bridge",
      start: async () => {
        await plugin.wsServer.start();
      },
      stop: async () => {
        await plugin.wsServer.stop();
      },
    });
  }

  api.registerChannel({ plugin });
  void plugin.wsServer.start().catch(() => undefined);
  return plugin;
}

export default function register(api: OpenClawApi) {
  return activate(api);
}


export const __testing = {
  dispatchInbound,
};
