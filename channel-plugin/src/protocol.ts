export const protocolVersion = "0.1.0";

export const messageTypes = {
  hello: "hello",
  helloAck: "hello_ack",
  ping: "ping",
  inboundMessage: "inbound.message",
  outboundSendText: "outbound.send_text",
  ack: "ack",
  error: "error",
} as const;

export type MessageType = (typeof messageTypes)[keyof typeof messageTypes];

export type HelloMessage = {
  type: typeof messageTypes.hello;
  id: string;
  timestamp: number;
  connector: {
    instanceId: string;
    version: string;
    provider: string;
  };
  auth: {
    scheme: "shared_secret";
    token: string;
  };
  accounts: string[];
  capabilities: string[];
};

export type InboundMessage = {
  type: typeof messageTypes.inboundMessage;
  id: string;
  timestamp: number;
  traceId?: string;
  accountId: string;
  peer: {
    type: "dm";
    userId: string;
  };
  message: {
    id: string;
    text: string;
  };
  source?: {
    provider?: string;
    eventType?: string;
  };
};

export type OutboundSendTextMessage = {
  type: typeof messageTypes.outboundSendText;
  id: string;
  timestamp: number;
  traceId?: string;
  accountId: string;
  peer: {
    type: "dm";
    userId: string;
  };
  text: string;
};

export type AckMessage = {
  type: typeof messageTypes.ack;
  id: string;
  replyTo: string;
  timestamp: number;
  status: "accepted" | "delivered" | "duplicate" | "pong";
};

export type ErrorMessage = {
  type: typeof messageTypes.error;
  id: string;
  replyTo?: string;
  timestamp: number;
  code: string;
  message: string;
};
