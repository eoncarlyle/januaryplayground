import {createContext} from "react";
import {IAuthContext} from "@/model.ts";

export const AuthContext = createContext<IAuthContext | null>(null);
