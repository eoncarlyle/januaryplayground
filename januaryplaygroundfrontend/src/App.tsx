import {useEffect, useState} from "react";
import {Route, Switch} from "wouter";

import "./App.css";
import Home from "./components/Home";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import {AuthState} from "./model";
import {getBaseUrl, loggedOutAuthState, usePersistentAuth} from "./util/rest";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";
import {useAuth} from "./util/queries";

function InternalApp() {
  const [authState, setAuth] = useState<AuthState>(loggedOutAuthState);
  const [persistentAuthState, setPersistentAuth] = usePersistentAuth();

  const { data: authData, status: authStatus, error: authError } = useAuth();

  useEffect(() => {
    const landingAuth = async () => {
      if (authState.evaluated) {
        return;
      } else if (persistentAuthState.loggedIn && authState.loggedIn) {
        setAuth({...authState, evaluated: true});
        return;
      } else if (
        persistentAuthState.loggedIn &&
        !authState.loggedIn &&
        authState.expireTime > Math.floor(Date.now() / 1000)
      ) {
        setAuth({
          email: persistentAuthState.email,
          loggedIn: true,
          expireTime: persistentAuthState.expireTime,
          evaluated: true,
        });
      } else {

        //const auth = await fetch(`${getBaseUrl()}/auth/evaluate`, {
        //  method: "POST",
        //  credentials: "include",
        //});

        if (authStatus === "success") {
          const newAuthState = authData
          setAuth(newAuthState)
          setPersistentAuth(newAuthState)

          setTimeout(() => {
            setAuth(loggedOutAuthState);
            setPersistentAuth(loggedOutAuthState);
          }, (newAuthState.expireTime * 1000) - Date.now());
        } else if (authStatus === "error") {
          setAuth({
            evaluated: true,
            email: null,
            loggedIn: false,
            expireTime: -1,
          });
          setPersistentAuth({
            email: null,
            loggedIn: false,
            expireTime: -1,
          });
        }
      }
    };
    landingAuth();
  }, [authData, authState, authStatus, persistentAuthState, setPersistentAuth]);

  /* Reflect on the fact that you did not immediately understand that if the first was allowed, the
      second would neccesarily be allowed
    ```js
    <Route path="/signup" component={SignUpWithOutProps} />
    <Route
      path="/signup"
      component={() => (
        <SignUp authState={authState} setAuthState={setAuthState} />
      )}
    />
    ```
  */

  const authProps = {
    authState: authState,
    setAuth: setAuth,
    persistentAuthState: persistentAuthState,
    setPersistentAuth: setPersistentAuth,
  };

  if (authState.evaluated) {
    return (
      <Switch>
        <Route path="/signup" component={() => <SignUp {...authProps} />}/>
        <Route path="/login" component={() => <LogIn {...authProps} />}/>
        <Route path="/" component={() => "Landing Page"}/>
        <Route path="/home" component={() => <Home {...authProps} />}/>
      </Switch>
    );
  } else {
    return <> </>;
  }
}

export default function App() {
  const queryClient = new QueryClient();
  return <QueryClientProvider client={queryClient}>
    <InternalApp/>
  </QueryClientProvider>
}
