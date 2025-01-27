import { AuthProps, AuthState, SetAuthState } from "@/model.ts";
import AuthContext from "@/util/AuthContext.ts";
import React, { useContext } from "react";
import { UseFormReturn } from "react-hook-form";

const EMAIL = "email";
const LOGGED_IN = "loggedIn";
const EXPIRE_TIME = "expireTime";

type FormType = UseFormReturn<{
  email: string;
  password: string;
}>;

type SetLocationType = <S = any>(
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

// TODO: This needs to be adjusted to work with login 403s, I don't think the
// feedback to the server is working correctly
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
      } else {
        throw new Error(`Bad result ${result}`);
      }
    } catch (e: unknown) {
      console.error(`Fetch failed: ${e}`);
    }
  };
}

/*
What I need is just
- Change the backend to include when the session will inspire
  - Save both in storage and in global state
- Write async job that logs out when the tu
- Write function that logs out (state and cookies) if 403s are hit


 */
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

function setAuthLocalStorage(authState: AuthState) {
  const { loggedIn, email, expireTime } = authState;
  console.log(authState);
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
