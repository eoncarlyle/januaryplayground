import {
  AllQuotesMessage,
  LandingPageState,
  PublicWebsocketMessage,
  QuoteMessage,
  StatelessQuote,
  publicWebsocketMesageSchema,
  quoteMessageSchema,
} from "@/util/model.ts";
import { allQuotesMessageSchema } from "@/util/model.ts";
import { Dispatch, SetStateAction } from "react";

export function getBaseUrl(): string {
  const baseurl: unknown = import.meta.env.VITE_API_DOMAIN;
  if (typeof baseurl === "string") {
    return baseurl;
  } else {
    throw new Error("Empty `baseurl`");
  }
}

type TypedPublicWebsocketMessage =
  | { isQuote: false; data: PublicWebsocketMessage }
  | {
      isQuote: true;
      data: QuoteMessage | AllQuotesMessage;
    };

export const parseWebsocketMessage = (
  input: unknown,
): TypedPublicWebsocketMessage | null => {
  for (const schema of publicWebsocketMesageSchema) {
    const result = schema.safeParse(input);
    //if (result.success && (schema === quoteMessageSchema || schema === allQuotesMessageSchema)) {
    if (result.success) {
      return schema === quoteMessageSchema || schema === allQuotesMessageSchema
        ? {
            isQuote: true,
            data: result.data as QuoteMessage | AllQuotesMessage,
          }
        : { isQuote: false, data: result.data };
    }
  }
  return null;
};

export const parseQuoteMessage = (
  msg: QuoteMessage | AllQuotesMessage,
): StatelessQuote => {
  if (msg.type === "outgoingQuote") {
    return msg.quote;
  } else {
    // Only will make sense for demo, currently doing single-ticker
    return msg.quotes[0];
  }
};

export async function setupPublicWebsocket(
  socket: WebSocket,
  setPageState: Dispatch<SetStateAction<LandingPageState>>,
) {
  socket.onmessage = (event) => {
    try {
      const data = JSON.parse(event.data);
      const parseResult = parseWebsocketMessage(data);

      if (!parseResult) {
        console.error(`Failed to parse WebSocket message:`, data);
        return;
      }

      setPageState((prevState) => {
        const updates: Partial<LandingPageState> = {
        };

        if (parseResult.isQuote) {
          const incomingQuote = parseQuoteMessage(parseResult.data);
          if (
            incomingQuote.ask !== -1 &&
            incomingQuote.ask !== -1
          ) {
            updates.quote = parseQuoteMessage(parseResult.data);
          }

          const maybeLastQuote = prevState.displayedMessages
            .slice()
            .reverse()
            .find((msg): msg is QuoteMessage | AllQuotesMessage => 'quote' in msg || 'quotes' in msg);
          const lastQuote = maybeLastQuote ? parseQuoteMessage(maybeLastQuote) : null;

          if (!lastQuote || (lastQuote.bid != incomingQuote.bid || lastQuote.ask != incomingQuote.ask)){
            updates.displayedMessages =  [
              ...prevState.displayedMessages,
              parseResult.data,
            ].slice(-10)
          }

        } else {
          updates.displayedMessages =  [
            ...prevState.displayedMessages,
            parseResult.data,
          ].slice(-10)
        }

        return { ...prevState, ...updates };
      });
    } catch (error) {
      console.error("Error processing WebSocket message:", error, event.data);
    }
  };

  socket.onerror = (error) => {
    console.error("WebSocket error:", error);
  };

  socket.onclose = (event) => {
    console.log("WebSocket connection closed:", event.code, event.reason);
  };

  socket.onopen = () => {
    console.log("WebSocket connection established");
  };
}
