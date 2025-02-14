import {
  AuthProps,
  PersistentAuthState,
  SetAuth,
  SetPersistentAuth,
  SetSocketMessageState,
  SetSocketState,
  TempSessionAuth,
} from "@/model.ts";
import { UseFormReturn } from "react-hook-form";

const EMAIL = "email";
const LOGGED_IN = "loggedIn";
const EXPIRE_TIME = "expireTime";

type FormType = UseFormReturn<{
  email: string;
  password: string;
}>;

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

export function createAuthOnSubmitHandler<T>(
  form: FormType,
  setAuth: SetAuth,
  endpoint: "signup" | "login",
) {
  return async (data: T) => {
    try {
      const result = await fetch(`${getBaseUrl()}/auth/${endpoint}`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        credentials: "include",
        body: JSON.stringify(data),
      });

      if (result.ok) {
        const authBody = await result.json();
        if (
          typeof authBody === "object" &&
          authBody !== null &&
          "email" in authBody &&
          "expireTime" in authBody
        ) {
          const newAuth = {
            evaluated: false,
            email: authBody.email,
            loggedIn: true,
            expireTime: authBody.expireTime,
          };
          setAuth(newAuth);
        } else {
          form.setError("root", {
            type: "server",
            message: "Something went wrong",
          });
        }
      } else {
        const errorMessage: unknown = await result.text();
        if (
          result.status < 500 &&
          errorMessage &&
          typeof errorMessage === "string"
        ) {
          form.setError("root", {
            type: "server",
            message: errorMessage,
          });
        } else {
          form.setError("root", {
            type: "server",
            message: "Something went wrong",
          });
        }
      }
    } catch (e: unknown) {
      console.error(`Fetch failed: ${e}`);
    }
  };
}

export function logOutHandler(setAuth: SetAuth, redirectOnSuccess: () => void) {
  return async () => {
    try {
      const result = await fetch(`${getBaseUrl()}/auth/logout`, {
        method: "POST",
        credentials: "include",
      });

      if (result.ok) {
        setAuth(loggedOutAuthState);
        setPersistentAuth(loggedOutAuthState);
        redirectOnSuccess();
      } else {
        const errorMessage: unknown = await result.text();
        if (
          result.status < 500 &&
          errorMessage &&
          typeof errorMessage === "string"
        ) {
          console.error(`Log out response failure: ${errorMessage}`);
        } else {
          console.error(`Log out response failure: server error`);
        }
      }
    } catch (e: unknown) {
      console.error(`Fetch failed: ${e}`);
    }
  };
}

export function useAuthRedirect(
  requiresAuth: boolean,
  authProps: AuthProps,
  location: string,
  setLocation: SetLocationType,
) {
  const authState = authProps.authState;

  if (requiresAuth && !authState.loggedIn) {
    setLocation("/login");
  } else if (
    !requiresAuth &&
    authState.loggedIn &&
    ["/login", "/signup"].includes(location)
  ) {
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

export async function setupWebsocket(
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
          type: "lifecycle",
          token: tempSessionAuth.token,
          email: email,
          operation: "authenticate",
        }),
      );
    } else {
      socket.onopen = (_event) => {
        socket.send(
          JSON.stringify({
            type: "lifecycle",
            token: tempSessionAuth.token,
            email: email,
            operation: "authenticate",
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
