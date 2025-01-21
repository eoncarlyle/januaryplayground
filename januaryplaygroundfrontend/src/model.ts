import React from "react";

export interface AuthState {
  email: string | null;
  loggedIn: boolean;
}

export interface IAuthContext {
  authState: AuthState;
  setAuthState: React.Dispatch<React.SetStateAction<AuthState>>;
}
