import { useEffect, useState } from "react";
import { Route, Switch } from "wouter";

import "./App.css";
import Home from "./components/Home";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import { AuthState } from "./model";
import {
  getBaseUrl,
  loggedOutAuthState,
  useAuthLocalStorage,
} from "./util/rest";

function App() {
  const [authState, setAuthState] = useState<AuthState>(loggedOutAuthState);
  const [authLocalStorage, setAuthLocalStorage] = useAuthLocalStorage();
  console.log(authState);

  useEffect(() => {
    const landingAuth = async () => {
      if (authState.evaluated) {
        return;
      } else if (authLocalStorage.loggedIn && authState.loggedIn) {
        setAuthState({ ...authState, evaluated: true });
        return;
      } else if (
        authLocalStorage.loggedIn &&
        !authState.loggedIn &&
        authState.expireTime > Math.floor(Date.now() / 1000)
      ) {
        setAuthState({
          email: authLocalStorage.email,
          loggedIn: true,
          expireTime: authLocalStorage.expireTime,
          evaluated: true,
        });
      } else {
        const auth = await fetch(`${getBaseUrl()}/auth/evaluate`, {
          credentials: "include",
        });
        try {
          if (auth.ok) {
            const body = await auth.json();
            setAuthState({ ...authState, evaluated: true });
            if (
              typeof body === "object" &&
              body !== null &&
              "email" in body &&
              "expireTime" in body
            ) {
              const expireTime = parseInt(body.expireTime);
              const newAuthState = {
                evaluated: true,
                email: body.email,
                loggedIn: true,
                expireTime: expireTime,
              };

              setAuthState(newAuthState);
              setAuthLocalStorage(newAuthState);
              setTimeout(() => {
                setAuthState(loggedOutAuthState);
                setAuthLocalStorage(loggedOutAuthState);
              }, Date.now() - expireTime);
            }
          } else {
            setAuthState({
              evaluated: true,
              email: null,
              loggedIn: false,
              expireTime: -1,
            });
            setAuthLocalStorage({
              email: null,
              loggedIn: false,
              expireTime: -1,
            });
          }
        } catch (_e) {
          setAuthState({
            evaluated: true,
            email: null,
            loggedIn: false,
            expireTime: -1,
          });
          setAuthLocalStorage({
            email: null,
            loggedIn: false,
            expireTime: -1,
          });
        }
      }
    };
    landingAuth();
  }, [authState, authLocalStorage, setAuthLocalStorage]);

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

  if (authState.evaluated) {
    return (
      <Switch>
        <Route
          path="/signup"
          component={() => (
            <SignUp authState={authState} setAuthState={setAuthState} />
          )}
        />
        <Route
          path="/login"
          component={() => (
            <LogIn authState={authState} setAuthState={setAuthState} />
          )}
        />
        <Route path="/" component={() => "Landing Page"} />
        <Route
          path="/home"
          component={() => (
            <Home authState={authState} setAuthState={setAuthState} />
          )}
        />
      </Switch>
    );
  } else {
    return <> </>;
  }
}

export default App;

//<Routes>
//<Route path="/signup" element={<SignUp />} />
//  <Route path="/login" element={<LogIn // />
//  <Route path="/" element={() => "Landing Page"} />
//  <Route path="/home" element={Home} />
//</Routes>
