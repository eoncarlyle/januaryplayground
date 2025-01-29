import { AuthProps, AuthState, SetAuthState } from "@/model.ts";
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
  setAuth: SetAuthState,
  redirectOnSuccess: () => void,
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
            email: authBody.email,
            loggedIn: true,
            expireTime: authBody.expireTime,
          };
          setAuth(newAuth);
          //setAuthLocalStorage(newAuth);
          // May become an issue?
          redirectOnSuccess();
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

export function logOutHandler(
  setAuth: SetAuthState,
  redirectOnSuccess: () => void,
) {
  return async () => {
    try {
      const result = await fetch(`${getBaseUrl()}/auth/logout`, {
        method: "POST",
        credentials: "include",
      });

      if (result.ok) {
        setAuth(loggedOutAuthState);
        setAuthLocalStorage(loggedOutAuthState);
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
  setLocation: SetLocationType,
  authProps: AuthProps,
) {
  if (requiresAuth && !authProps.authState.loggedIn) {
    setLocation("/login");
  } else if (!requiresAuth && authProps.authState.loggedIn) {
    setLocation("/home");
  }
}

export const loggedOutAuthState = {
  email: null,
  loggedIn: false,
  expireTime: -1,
};

function setAuthLocalStorage(authState: AuthState) {
  const { loggedIn, email, expireTime } = authState;
  localStorage.setItem(LOGGED_IN, loggedIn ? "true" : "false");
  if (email) {
    localStorage.setItem(EMAIL, email);
  }
  localStorage.setItem(EXPIRE_TIME, expireTime.toString());
}

export function useAuthLocalStorage(): [
  AuthState,
  (authState: AuthState) => void,
] {
  const maybeExpireTime = localStorage.getItem(EXPIRE_TIME);

  const authLocalStorage: AuthState = {
    email: localStorage.getItem(EMAIL),
    loggedIn: localStorage.getItem(LOGGED_IN) === "true",
    expireTime: maybeExpireTime ? parseInt(maybeExpireTime) : -1,
  };

  return [authLocalStorage, setAuthLocalStorage];
}
