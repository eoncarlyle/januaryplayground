import {publicWebsocketMesageSchema, PublicWebsocketMessage} from "@/util/model";
import { setupPublicWebsocket } from "@/util/rest.ts";
import { useEffect, useState } from "react";

export const parseWebsocketMessage = (input: unknown): PublicWebsocketMessage | null => {
  for (const schema of publicWebsocketMesageSchema) {
    const result = schema.safeParse(input);
    if (result.success) {
      return result.data;
    }
  }
  return null;
};

export const safeStringParse = (input: PublicWebsocketMessage | null): string => {
  return input ? JSON.stringify(input) : "null";
}

export function Landing() {
  const [msgs, setMsgs] = useState<object[]>([]);

  useEffect(() => {
    const socket = new WebSocket("ws://localhost:7070/ws/public");
    setupPublicWebsocket(socket, setMsgs);

    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, []);
  return (
    <>
      {msgs.map((msg) => (
        <p>{safeStringParse(parseWebsocketMessage(msg))}</p>
      ))}
    </>
  );
  //return <>{msgs.map(msg => <p>{msg}</p>)}</>
}
