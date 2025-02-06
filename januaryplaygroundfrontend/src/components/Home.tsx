import { useEffect, useState } from "react";
import { useLocation } from "wouter";

import { AuthProps, isFetching } from "../model";
import { setupWebsocket, useAuthRedirect } from "../util/rest";
import AuthNavBar from "./AuthNavBar";
import Layout from "./Layout";

export default function Home(authProps: AuthProps) {
  const [location, setLocation] = useLocation();

  const [socketState, setSocketState] = useState<null | WebSocket>(null);
  const [socketMessageState, setSocketMessageState] = useState("");

  useEffect(() => {
    if (socketState) return;
    if (isFetching(authProps.authState)) return;
    const socket = new WebSocket("ws://localhost:7070/ws");

    setupWebsocket(
      //TODO fix, is ugly
      // @ts-expect-error Types have been narrowed
      authProps.authState.email || "",
      socket,
      setSocketState,
      setSocketMessageState,
    );

    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, [socketState, authProps.authState]);
  // Check auth if we know it is wrong
  useAuthRedirect(true, authProps, location, setLocation);
  console.log(socketMessageState);
  return (
    <Layout>
      <>
        <AuthNavBar
          authState={authProps.authState}
          setAuthState={authProps.setAuthState}
        />
        <div className="m-2 flex justify-center">
          {authProps.authState.email}
        </div>
      </>
    </Layout>
  );
}
