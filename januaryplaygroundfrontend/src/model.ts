import React from "react";

export interface BaseAuth {
  email: string | null;
  loggedIn: boolean;
  expireTime: number;
}

export interface AuthState extends BaseAuth {
  evaluated: boolean;
}

export type StoredAuthState = BaseAuth;

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
