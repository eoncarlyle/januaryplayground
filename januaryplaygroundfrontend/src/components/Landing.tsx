import { PublicWebsocketMessage } from "@/util/model";
import { setupPublicWebsocket } from "@/util/rest.ts";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";

export function Landing() {
  const [msgs, setMsgs] = useState<PublicWebsocketMessage[]>([]);

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
        <p>{JSON.stringify(msg)}</p>
      ))}
    </>
  );
}
