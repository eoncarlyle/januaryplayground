import {
  PersistentAuthState,
  SetPersistentAuth,
  SetSocketMessageState,
  SetSocketState,
  TempSessionAuth,
} from "@/model.ts";
import { Dispatch, SetStateAction } from "react";

import {
  AuthDto,
  PublicWebsocketMessage,
  publicWebsocketMesageSchema,
} from "./model";

const EMAIL = "email";
const LOGGED_IN = "loggedIn";
const EXPIRE_TIME = "expireTime";

type SetLocationType = <S>(
  to: string | URL,
  options?: {
    replace?: boolean;
    state?: S;
  },
) => void;

export function getBaseUrl(): string {
  const baseurl: unknown = import.meta.env.VITE_API_DOMAIN;
  if (typeof baseurl === "string") {
    return baseurl;
  } else {
    throw new Error("Empty `baseurl`");
  }
}

export function useAuthRedirect(
  requiresAuth: boolean,
  authDto: AuthDto | undefined,
  location: string,
  setLocation: SetLocationType,
) {
  if (!authDto && requiresAuth) {
    setLocation("/login");
  } else if (
    authDto &&
    !requiresAuth &&
    ["/login", "/signup"].includes(location)
  ) {
    console.log("here!", authDto, requiresAuth, location);
    setLocation("/home");
  }
}

export async function getWebsocketAuth(
  email: string,
): Promise<TempSessionAuth | null> {
  return fetch(`${getBaseUrl()}/auth/sessions/temporary`, {
    method: "POST",
    credentials: "include",
    body: JSON.stringify({ email: email }),
  })
    .then((auth) => auth.json())
    .then((body) => {
      if (typeof body === "object" && body !== null && "token" in body) {
        return { token: body["token"] };
      } else return null;
    });
}

//typeof authBody === "object" &&
//authBody !== null &&
//"email" in authBody &&
//"expireTime" in authBody

export async function setupAuthenticatedWebsocket(
  email: string,
  socket: WebSocket,
  setSocketState: SetSocketState,
  setSocketMessageState: SetSocketMessageState,
) {
  const tempSessionAuth = await getWebsocketAuth(email);

  if (
    tempSessionAuth &&
    typeof tempSessionAuth === "object" &&
    "token" in tempSessionAuth
  ) {
    if (socket.readyState === WebSocket.OPEN) {
      socket.send(
        JSON.stringify({
          type: "clientLifecycle",
          token: tempSessionAuth.token,
          email: email,
          operation: "authenticate",
          tickers: [],
        }),
      );
    } else {
      socket.onopen = (_event) => {
        socket.send(
          JSON.stringify({
            type: "clientLifecycle",
            token: tempSessionAuth.token,
            email: email,
            operation: "authenticate",
            tickers: [],
          }),
        );
      };
    }

    socket.onmessage = (event) => {
      setSocketMessageState(event.data);
    };

    socket.onerror = (event) => {
      console.error("WebSocket error:", event);
    };

    socket.onclose = () => {
      setSocketState(null);
    };
  } else {
    console.error(`Websocket error, temp session auth: ${tempSessionAuth}`);
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

export const loggedOutAuthState = {
  evaluated: false,
  email: null,
  loggedIn: false,
  expireTime: -1,
};

function setPersistentAuth(persistentAuthState: PersistentAuthState) {
  const { loggedIn, email, expireTime } = persistentAuthState;
  localStorage.setItem(LOGGED_IN, loggedIn ? "true" : "false");
  if (email) {
    localStorage.setItem(EMAIL, email);
  }
  localStorage.setItem(EXPIRE_TIME, expireTime.toString());
}

export function usePersistentAuth(): [PersistentAuthState, SetPersistentAuth] {
  const maybeExpireTime = localStorage.getItem(EXPIRE_TIME);

  const persistentAuthState: PersistentAuthState = {
    email: localStorage.getItem(EMAIL),
    loggedIn: localStorage.getItem(LOGGED_IN) === "true",
    expireTime: maybeExpireTime ? parseInt(maybeExpireTime) : -1,
  };

  return [persistentAuthState, setPersistentAuth];
}
