import React from "react";

export interface AuthState {
  email: string | null;
  loggedIn: boolean;
  expireTime: number;
}

export type SetAuthState = React.Dispatch<React.SetStateAction<AuthState>>;

export interface AuthProps {
  authState: AuthState;
  setAuthState: SetAuthState;
}
