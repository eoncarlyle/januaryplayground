import {useEffect, useState} from "react";
import {setupPublicWebsocket} from "@/util/rest.ts";
import {incomingSchemaList} from "@/util/model";

export const validationTest = (input: unknown) => {
  const parsedResult = incomingSchemaList.find(s => s.safeParse(input).success);
  if (parsedResult) {
    return parsedResult.parse.toString()
  } else {
    return "error";
  }
}

export function Landing() {

  const [msgs, setMsgs] = useState<string[]>([]);

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
  return <>{msgs.map(msg => <p>{validationTest(msg)}</p>)}</>
  //return <>{msgs.map(msg => <p>{msg}</p>)}</>
}
