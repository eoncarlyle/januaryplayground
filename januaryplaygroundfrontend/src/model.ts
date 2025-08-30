import React from "react";
import z from "zod";

export interface BaseAuth {
  email: string | null;
  loggedIn: boolean;
  expireTime: number;
}

export interface AuthState extends BaseAuth {
  evaluated: boolean;
}

export type PersistentAuthState = BaseAuth;

export type SetAuth = React.Dispatch<React.SetStateAction<AuthState>>;

export type SetPersistentAuth = (
  persistentAuthState: PersistentAuthState,
) => void;

export interface TempSessionAuth {
  token: string;
}

export type SocketState = WebSocket | null;

export type SetSocketState = React.Dispatch<React.SetStateAction<SocketState>>;

export type SetSocketMessageState = React.Dispatch<
  React.SetStateAction<string>
>;

export const quoteMessageSchema = z.object({
  quote: z.object({
    ticker: z.string(),
    bid: z.number(),
    ask: z.number(),
    exchangeSequenceTimestamp: z.number(),
  }),
  type: z.literal("outgoingQuote")
})

export type QuoteMessage = z.infer<typeof quoteMessageSchema>

export const creditTransferDtoSchema = z.object({
  sendingUserEmail: z.string(),
  targetUserEmail: z.string(),
  creditAmount: z.number(),
})

export type CreditTransferDto = z.infer<typeof creditTransferDtoSchema>

const tradeTypeSchema = z.enum(['BUY', 'SELL']);
const orderTypeSchema = z.enum(['Market', 'Limit']);

export const marketOrderRequestSchema = z.object({
  type: z.string().default('incomingOrder'),
  email: z.string().email(),
  ticker: z.string(),
  size: z.number().int().positive(),
  tradeType: tradeTypeSchema,
  orderType: z.literal('Market'),
});

export const limitOrderRequestSchema = z.object({
  type: z.string().default('incomingOrder'),
  email: z.string().email(),
  ticker: z.string(),
  size: z.number().int().positive(),
  tradeType: tradeTypeSchema,
  orderType: z.literal('Limit'),
  price: z.number().int().positive(),
});


export const orderFilledSchema = z.object({
  ticker: z.string(),
  positionId: z.number(),
  exchangeSequenceTimestamp: z.number(),
  tradeType: tradeTypeSchema,
  orderType: orderTypeSchema,
  size: z.number().int(),
  email: z.string(),
  subtype: z.literal("orderFilled"),
});

export const orderAcknowledgedSchema = z.object({
  ticker: z.string(),
  orderId: z.number(),
  exchangeSequenceTimestamp: z.number(),
  tradeType: tradeTypeSchema,
  orderType: orderTypeSchema,
  size: z.number().int(),
  email: z.string(),
  subtype: z.literal("orderAcknowledged"),
});

export const orderPartiallyFilledSchema = z.object({
  ticker: z.string(),
  positionId: z.number(),
  exchangeSequenceTimestamp: z.number(),
  restingOrderId: z.number(),
  tradeType: tradeTypeSchema,
  orderType: orderTypeSchema,
  size: z.number().int(),
  email: z.string(),
  subtype: z.literal("orderPartiallyFilled"),
});


export const statelessQuoteSchema = z.object({
  ticker: z.string(),
  bid: z.number(),
  ask: z.number(),
})

export const exchangeRequestDtoSchema = z.object({
  email: z.string(), //z.string().email(): should eventually, keeping simple for now
  ticker: z.string(),
})

const someOrdersCancelledSchema = z.object({
  ticker: z.string(),
  orders: z.number().int(),
  exchangeSequenceTimestamp: z.number(),
});

const noOrdersCancelledSchema = z.object({
  exchangeSequenceTimestamp: z.number(),
});

export const marketOrderQueueMessageSchema = z.object({
  request: marketOrderRequestSchema,
  response: orderFilledSchema,
  initialStatelessQuote: statelessQuoteSchema.nullish(),
  finalStatelessQuote: statelessQuoteSchema.nullish(),
})

export const limitOrderQueueMessageSchema = z.object({
  request: limitOrderRequestSchema,
  response: z.union([orderFilledSchema, orderPartiallyFilledSchema, orderAcknowledgedSchema]),
  initialStatelessQuote: statelessQuoteSchema.nullish(),
  finalStatelessQuote: statelessQuoteSchema.nullish(),
})

export const cancelOrderQueueMessageSchema = z.object({
  request: exchangeRequestDtoSchema,
  response: z.union([someOrdersCancelledSchema, noOrdersCancelledSchema]),
  initialStatelessQuote: statelessQuoteSchema.nullish(),
  finalStatelessQuote: statelessQuoteSchema.nullish(),
})