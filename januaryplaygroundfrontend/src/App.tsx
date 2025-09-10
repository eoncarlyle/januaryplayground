import { Landing } from "@/components/Landing.tsx";
import { Route, Switch } from "wouter";

import "./App.css";

function InternalApp() {
  return (
    <Switch>
      <Route path="/" component={() => <Landing />} />
    </Switch>
  );
}

export default function App() {
  return <InternalApp />;
}
