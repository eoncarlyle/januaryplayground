import "./App.css";
import { SignUp } from "./components/SignUp";

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
    <>
      <SignUp />
    </>
  );
}

export default App;
