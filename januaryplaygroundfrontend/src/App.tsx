import { Landing } from "@/components/Landing.tsx";
import { Route, Switch } from "wouter";

import "./App.css";

function InternalApp() {
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
      {/*<Route path="/signup" component={() => <SignUp />} />*/}
      {/*<Route path="/login" component={() => <LogIn />} />*/}
      <Route path="/" component={() => <Landing />} />
      {/*<Route path="/home" component={() => <Home />} />*/}
    </Switch>
  );
}

export default function App() {
  return <InternalApp />;
}
