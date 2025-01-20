import {useEffect, useState} from "react";
import {Route, Switch, useLocation} from "wouter";

import "./App.css";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import {evaluateAppAuth, getBaseUrl, useAuthRedirect} from "./util/rest";
import {AuthState} from "@/model.ts";
import {AuthContext} from "./util/AuthContext";

function Home() {
  const [response, setResponse] = useState<string>("");
  //const [_, setLocation] = useLocation(); 

  //useAuthRedirect(true, setLocation)

  useEffect(() => {
    const evaluateAuth = async () => {
      fetch(`${getBaseUrl()}/auth/evaluate`, {
        credentials: "include",
      })
        .then((auth) => auth.text())
        .then((text) => {
          if (text != response) {
            console.log(text);
            setResponse(text);
          }
        });
    }
    
    evaluateAuth()
  }, []);

  return response;
}

const defaultAuth: AuthState = {email: null, loggedIn: false}

function App() {
  const [authState, setAuthState] = useState(defaultAuth);

  useEffect(() => {
    (async () => {
      await evaluateAppAuth({email: null, loggedIn: false}, setAuthState);
    })();
  }, [authState]);

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
  return (
    <AuthContext.Provider value={{authState: authState, setAuthState: setAuthState}}>
      <Switch>
        <Route path="/signup" component={SignUp}/>
        <Route path="/login" component={LogIn}/>
        <Route path="/" component={() => "Landing Page"}/>
        <Route path="/home" component={Home}/>
      </Switch>
    </AuthContext.Provider>
  );
}

export default App;
