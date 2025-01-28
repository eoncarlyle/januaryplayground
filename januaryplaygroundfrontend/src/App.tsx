import { Button } from "@/components/ui/button";
import { useEffect, useState } from "react";
import { Route, Switch, useLocation } from "wouter";

import "./App.css";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import { Card } from "./components/ui/card";
import { AuthProps, AuthState } from "./model";
import {
  getBaseUrl,
  loggedOutAuthState,
  useAuthLocalStorage,
  useAuthRedirect,
} from "./util/rest";

function Layout(props: { children: JSX.Element }) {
  return (
    <div className="flex flex-col min-h-screen w-screen">
      <NavBar />
      <main className="flex-1">{props.children}</main>
    </div>
  );
}

function NavBar() {
  return (
    <nav className="border-b">
      <div className="flex h-16 items-center px-4 w-full">
        <div className="flex items-center space-x-2">
          <span className="text-xl font-semibold">Janurary Playground</span>
        </div>
        <div className="flex-1" />

        <Button
          onClick={() => console.log("clicked")}
          className="flex items-center space-x-2"
        >
          <span> Log out </span>
        </Button>
      </div>
    </nav>
  );
}

function Home(authPops: AuthProps) {
  const [_location, setLocation] = useLocation();

  // Check auth if we know it is wrong
  useAuthRedirect(true, setLocation, authPops);

  return (
    <Layout>
      <div className="flex items-center justify-center h-full p-2">
        <Card>
          <div className="m-2">{authPops.authState.email}</div>
        </Card>
      </div>
    </Layout>
  );
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
              const expireTime = parseInt(evalBody.expireTime);
              const newAuthState = {
                email: evalBody.email,
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
