import { useEffect, useState } from "react";
import { Route, Switch } from "wouter";

import "./App.css";
import Home from "./components/Home";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import { AuthState } from "./model";
import { getBaseUrl, loggedOutAuthState, usePersistentAuth } from "./util/rest";

function App() {
  const [authState, setAuth] = useState<AuthState>(loggedOutAuthState);
  const [persistentAuthState, setPersistentAuth] = usePersistentAuth();

  console.log("App Auth State", authState);
  console.log("Auth Local Storage", persistentAuthState);
  useEffect(() => {
    const landingAuth = async () => {
      if (authState.evaluated) {
        return;
      } else if (persistentAuthState.loggedIn && authState.loggedIn) {
        setAuth({ ...authState, evaluated: true });
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
        const auth = await fetch(`${getBaseUrl()}/auth/evaluate`, {
          credentials: "include",
        });
        try {
          if (auth.ok) {
            const body = await auth.json();
            setAuth({ ...authState, evaluated: true });
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

              setAuth(newAuthState);
              setPersistentAuth(newAuthState);
              setTimeout(() => {
                setAuth(loggedOutAuthState);
                setPersistentAuth(loggedOutAuthState);
              }, expireTime - Date.now());
            }
          } else {
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
        } catch (_e) {
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
  }, [authState, persistentAuthState, setPersistentAuth]);

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
        <Route path="/signup" component={() => <SignUp {...authProps} />} />
        <Route path="/login" component={() => <LogIn {...authProps} />} />
        <Route path="/" component={() => "Landing Page"} />
        <Route path="/home" component={() => <Home {...authProps} />} />
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
