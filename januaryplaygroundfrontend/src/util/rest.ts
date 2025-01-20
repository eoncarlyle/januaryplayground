import IAppAuth from "@/model.ts";
import React, { useEffect } from "react";
import { UseFormReturn } from "react-hook-form";

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
  setLocation: SetLocationType,
) {
  return async (data: T) => {
    try {
      const result = await fetch(`${getBaseUrl()}/auth/signup`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        credentials: "include",
        body: JSON.stringify(data),
      });

      if (result.ok) {
        setLocation("/home");
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
  appAuthContext: IAppAuth,
  setAppAuthState: React.Dispatch<React.SetStateAction<IAppAuth>>,
) {
  if (appAuthContext.loggedIn) {
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
        setAppAuthState({ email: body.email, loggedIn: true });
      }
    } else {
      setAppAuthState({ email: null, loggedIn: false });
    }
  } catch (error: any) {
    console.error(`Evaluate app auth failed: ${error}`);
    setAppAuthState({ email: null, loggedIn: false });
  }
}
