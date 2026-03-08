export type QqdmAccountConfig = {
  accountId?: string;
  enabled?: boolean;
  label?: string;
};

export type QqdmConfig = {
  bindHost: string;
  bindPort: number;
  sharedSecret: string;
  defaultAgentId: string;
  sessionPrefix: string;
  accounts: Record<string, QqdmAccountConfig>;
};

export type ResolvedQqdmAccount = {
  accountId: string;
  enabled: boolean;
  label?: string;
};

export const defaultConfig: QqdmConfig = {
  bindHost: "127.0.0.1",
  bindPort: 19190,
  sharedSecret: "change-me",
  defaultAgentId: "main",
  sessionPrefix: "qqdm",
  accounts: {},
};

function normalizeAccounts(input?: Partial<QqdmConfig>): Record<string, QqdmAccountConfig> {
  if (!input?.accounts) {
    return { ...defaultConfig.accounts };
  }
  return Object.fromEntries(Object.entries(input.accounts).map(([accountId, config]) => [accountId, { ...config }]));
}

export function resolveConfig(input?: Partial<QqdmConfig>): QqdmConfig {
  return {
    ...defaultConfig,
    ...input,
    accounts: normalizeAccounts(input),
  };
}

export function listConfiguredAccountIds(config: QqdmConfig): string[] {
  return Object.keys(config.accounts);
}

export function resolveAccountConfig(config: QqdmConfig, accountId?: string | null): ResolvedQqdmAccount {
  const ids = listConfiguredAccountIds(config);
  const fallback = accountId && accountId.trim().length > 0 ? accountId : ids[0] ?? "default";
  const raw = config.accounts[fallback] ?? {};
  return {
    accountId: raw.accountId?.trim() || fallback,
    enabled: raw.enabled !== false,
    ...(raw.label ? { label: raw.label } : {}),
  };
}

export function createHelloAck(config: QqdmConfig, replyTo: string, accounts: string[]) {
  return {
    type: "hello_ack",
    id: `hello_ack_${replyTo}`,
    replyTo,
    timestamp: Date.now(),
    accepted: true,
    channel: {
      id: "qqdm",
      version: "0.1.0",
      defaultAgentId: config.defaultAgentId,
    },
    accounts,
    capabilities: ["dm_text_in", "dm_text_out", "ack", "ping"],
  };
}

export const qqdmConfigSchema = {
  schema: {
    type: "object",
    additionalProperties: false,
    properties: {
      bindHost: { type: "string" },
      bindPort: { type: "integer", minimum: 1, maximum: 65535 },
      sharedSecret: { type: "string" },
      defaultAgentId: { type: "string" },
      sessionPrefix: { type: "string" },
      accounts: {
        type: "object",
        additionalProperties: {
          type: "object",
          additionalProperties: false,
          properties: {
            accountId: { type: "string" },
            enabled: { type: "boolean" },
            label: { type: "string" },
          },
        },
      },
    },
  },
  uiHints: {
    bindHost: { label: "Bind Host" },
    bindPort: { label: "Bind Port" },
    sharedSecret: { label: "Shared Secret", sensitive: true },
    defaultAgentId: { label: "Default Agent" },
    sessionPrefix: { label: "Session Prefix" },
    accounts: { label: "QQ Accounts" },
  },
};
