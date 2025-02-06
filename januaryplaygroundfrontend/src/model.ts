import React from "react";

export interface CheckedAuthState {
  email: string | null;
  loggedIn: boolean;
  expireTime: number;
}

export interface Fetching {
  fetching: true;
}

export function isFetching(authState: AuthState): boolean {
  return (
    authState !== null &&
    typeof authState === "object" &&
    "fetching" in authState
  );
}

export type AuthState = CheckedAuthState | Fetching;

export type SetAuthState = React.Dispatch<React.SetStateAction<AuthState>>;

export interface AuthProps {
  authState: AuthState;
  setAuthState: SetAuthState;
}

export interface TempSessionAuth {
  token: string;
}

export type SocketState = WebSocket | null;

export type SetSocketState = React.Dispatch<React.SetStateAction<SocketState>>;

export type SetSocketMessageState = React.Dispatch<
  React.SetStateAction<string>
>;
