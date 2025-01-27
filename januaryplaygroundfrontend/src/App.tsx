import { useEffect, useState } from "react";
import { Route, Switch, useLocation } from "wouter";

import "./App.css";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import { AuthProps, AuthState } from "./model";
import { getBaseUrl, useAuthLocalStorage, useAuthRedirect } from "./util/rest";

function Home(authPops: AuthProps) {
  const [_location, setLocation] = useLocation();

  // Check auth if we know it is wrong
  useAuthRedirect(true, setLocation, authPops);

  return <>{authPops.authState.email}</>;
}

function App() {
  //useEffect(() => {
  //  (async () => {
  //    await evaluateAppAuth({ email: null, loggedIn: false }, setAuthState);
  //  })();
  //}, [authState]);

  /*
    const [count, setCount] = useState(0);
    const [socket, setSocket] = useState<null | WebSocket>(null);
    const [message, setMessage] = useState("");
    useEffect(() => {
      if (socket) return;
      const newSocket = new WebSocket("ws://localhost:7070/ws");
      setSocket(newSocket);

      newSocket.onopen = () => {
        console.log("WebSocket connected");
      };

      newSocket.onmessage = (event) => {
          setMessage(event.data);
      }

      newSocket.onerror = (event) => {
          console.error("WebSocket error:", event);
      }

      newSocket.onclose = () => {
          console.log("WebSocket disconnencted");
          setSocket(null);
      }

      return () => {
          if (newSocket.readyState === WebSocket.OPEN) {
              newSocket.close();
          }
      }

    }, [socket]);
     */

  // TODO start here: provide the state, setState in the auth context, this will rqeuire new types and that's fine

  // Check auth if we know it is wrong

  const [_response, setResponse] = useState<string>("");
  const [authState, setAuthState] = useState<AuthState>({
    email: null,
    loggedIn: false,
    expireTime: -1,
  });
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
        fetch(`${getBaseUrl()}/auth/evaluate`, {
          credentials: "include",
        })
          .then((auth) => auth.text())
          .then((text) => {
            const evalBody = JSON.parse(text);
            //TODO throwing error
            if (
              typeof evalBody === "object" &&
              evalBody !== null &&
              "email" in evalBody &&
              "expireTime" in evalBody
            ) {
              const newAuthState = {
                email: evalBody.email,
                loggedIn: true,
                expireTime: parseInt(evalBody.expireTime),
              };
              setAuthState(newAuthState);
              setAuthLocalStorage(newAuthState);
            }
            // check auth explicitly
            setResponse(text);
          })
          .catch((_err) =>
            setAuthLocalStorage({
              email: null,
              loggedIn: false,
              expireTime: -1,
            }),
          );
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
