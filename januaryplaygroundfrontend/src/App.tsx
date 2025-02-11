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
  const [_response, setResponse] = useState<string>("");
  const [authState, setAuthState] = useState<AuthState>(loggedOutAuthState);
  const [authLocalStorage, setAuthLocalStorage] = useAuthLocalStorage();

  useEffect(() => {
    const landingAuth = async () => {
      if (authLocalStorage.loggedIn && authState.loggedIn) {
        return;
      } else if (authLocalStorage.loggedIn && !authState.loggedIn) {
        setAuthState({
          email: authLocalStorage.email,
          loggedIn: true,
          expireTime: authLocalStorage.expireTime,
        });
      } else {
        const auth = await fetch(`${getBaseUrl()}/auth/evaluate`, {
          credentials: "include",
        });
        try {
          if (auth.ok) {
            const body = await auth.json();
            if (
              typeof body === "object" &&
              body !== null &&
              "email" in body &&
              "expireTime" in body
            ) {
              const expireTime = parseInt(body.expireTime);
              const newAuthState = {
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
            setResponse(body);
          } else {
            setAuthLocalStorage({
              email: null,
              loggedIn: false,
              expireTime: -1,
            });
          }
        } catch (_e) {
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
}

export default App;

//<Routes>
//<Route path="/signup" element={<SignUp />} />
//  <Route path="/login" element={<LogIn // />
//  <Route path="/" element={() => "Landing Page"} />
//  <Route path="/home" element={Home} />
//</Routes>
