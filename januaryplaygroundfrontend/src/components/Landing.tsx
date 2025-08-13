import {useEffect, useState} from "react";
import {setupPublicWebsocket} from "@/util/rest.ts";

export function Landing() {

  const [msgs, setMsgs] = useState<String[]>([]);

  useEffect(() => {
    const socket = new WebSocket("ws://localhost:7070/ws/public");
    setupPublicWebsocket(
      socket,
      setMsgs,
    );

    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, []);
  return <>{msgs.map(msg => <p>{msg}</p>)}</>
}