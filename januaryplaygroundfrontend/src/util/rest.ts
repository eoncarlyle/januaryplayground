import { AuthState } from "@/model.ts";
import AuthContext from "@/util/AuthContext.ts";
import React, { useContext } from "react";
import { UseFormReturn } from "react-hook-form";

const EMAIL = "email";
const LOGGED_IN = "loggedIn";

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

//TODO: need to prevent logged in user from accessing this, need a lightweight auth endpoint for this
export function createAuthOnSubmitHandler<T>(
  form: FormType,
  setAuth: (loggedIn: boolean) => void,
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
        setAuth(true);
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
    } catch (e: unknown) {
      console.error(`Fetch failed: ${e}`);
    }
  };
}

export async function evaluateAppAuth(
  authState: AuthState,
  setAuthState: React.Dispatch<React.SetStateAction<AuthState>>,
) {
  if (authState.loggedIn) {
    return;
  }

  try {
    const response = await fetch(`${getBaseUrl()}/auth/evaluate`, {
      credentials: "include",
    });

    if (response.ok) {
      const body: unknown = await response.json();
      if (
        body &&
        typeof body === "object" &&
        "email" in body &&
        typeof body.email === "string"
      ) {
        setAuthState({ email: body.email, loggedIn: true });
      }
    }
  } catch (error: any) {
    console.error(`Evaluate app auth failed: ${error}`);
  }
}

export function useAuthRedirect(
  requiresAuth: boolean,
  setLocation: SetLocationType,
) {
  const [authLocalStorage, _] = useAuthLocalStorage();
  if (requiresAuth && !authLocalStorage.loggedIn) {
    setLocation("/login");
  } else if (!requiresAuth && authLocalStorage.loggedIn) {
    setLocation("/home");
  }
}

export function useAuthLocalStorage(): [
  AuthState,
  (loggedIn: boolean, email?: string) => void,
] {
  const authLocalStorage: AuthState = {
    email: localStorage.getItem(EMAIL),
    loggedIn: localStorage.getItem(LOGGED_IN) === "true",
  };

  const setAuthLocalStorage = (loggedIn: boolean, email?: string) => {
    localStorage.setItem(LOGGED_IN, loggedIn ? "true" : "false");
    if (email) localStorage.setItem(EMAIL, email);
  };

  return [authLocalStorage, setAuthLocalStorage];
}
