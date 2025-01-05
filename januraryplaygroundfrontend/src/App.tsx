import { useState, useEffect } from "react";
import reactLogo from "./assets/react.svg";
import viteLogo from "/vite.svg";
import "./App.css";

function App() {
  const [count, setCount] = useState(0);
  const [socket, setSocket] = useState<null | WebSocket>(null);
  const [message, setMessage] = useState("");

  useEffect(() => {
    if (!socket) {
      const socket = new WebSocket("ws://localhost:7070/ws");
      socket.onopen = (_event) => {
        console.log("onopen called");

        socket.onmessage = (event) => {
          setMessage(event.data);
        };

        setSocket(socket);
      };
    }
  }, [socket]);

  return (
    <>
      <div>
        <a href="https://vite.dev" target="_blank">
          <img src={viteLogo} className="logo" alt="Vite logo" />
        </a>
        <a href="https://react.dev" target="_blank">
          <img src={reactLogo} className="logo react" alt="React logo" />
        </a>
      </div>
      <h1>Vite + React</h1>
      <div className="card">
        <button onClick={() => setCount((count) => count + 1)}>
          count is {count}
        </button>
        <p>
          Edit <code>src/App.tsx</code> and save to test HMR
        </p>
      </div>
      <p className="read-the-docs">
        Click on the Vite and React logos to learn more
      </p>
      {message}
    </>
  );
}

export default App;
