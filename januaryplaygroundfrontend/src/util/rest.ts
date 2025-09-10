import {
  PublicWebsocketMessage,
  publicWebsocketMesageSchema,
} from "@/util/model.ts";
import { Dispatch, SetStateAction } from "react";

export function getBaseUrl(): string {
  const baseurl: unknown = import.meta.env.VITE_API_DOMAIN;
  if (typeof baseurl === "string") {
    return baseurl;
  } else {
    throw new Error("Empty `baseurl`");
  }
}

export const parseWebsocketMessage = (
  input: unknown,
): PublicWebsocketMessage | null => {
  for (const schema of publicWebsocketMesageSchema) {
    const result = schema.safeParse(input);
    if (result.success) {
      return result.data;
    }
  }
  return null;
};

export async function setupPublicWebsocket(
  socket: WebSocket,
  setMsgs: Dispatch<SetStateAction<PublicWebsocketMessage[]>>,
) {
  socket.onmessage = (event) => {
    const parsedMessage = parseWebsocketMessage(JSON.parse(event.data));
    if (parsedMessage) {
      setMsgs((msgs) => [...msgs, parsedMessage].slice(-10));
    }
  };

  socket.onerror = (event) => {
    console.error("WebSocket error:", event);
  };
}
