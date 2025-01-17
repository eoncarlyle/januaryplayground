import { useEffect, useState } from "react";
import { Route, Switch } from "wouter";

import "./App.css";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import { getBaseUrl } from "./util/rest";

function Home() {
  const [response, setResponse] = useState<string>("");

  useEffect(() => {
    if (response === "") {
      fetch(`${getBaseUrl()}/auth/test`, {
        credentials: "include",
      })
        .then((auth) => auth.text())
        .then((text) => {
          console.log(text);
          setResponse(text);
        });
    } else {
      return;
    }
  }, [response, setResponse]);

  return response;
}

function App() {
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

  return (
    <Switch>
      <Route path="/signup" component={SignUp} />
      <Route path="/login" component={LogIn} />
      <Route path="/" component={() => "Landing Page"} />
      <Route path="/home" component={Home} />
    </Switch>
  );
}

export default App;
