import { Landing } from "@/components/Landing.tsx";
import { createAsyncStoragePersister } from "@tanstack/query-async-storage-persister";
import { QueryClient } from "@tanstack/react-query";
import {
  PersistQueryClientProvider,
  persistQueryClient,
} from "@tanstack/react-query-persist-client";
import { Route, Switch } from "wouter";

import "./App.css";
import Home from "./components/Home";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";

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

//TODO: Fix the query persistence
export default function App() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: {
        gcTime: 1000 * 60 * 60 * 24, // 24 hours
      },
    },
  });

  const persister = createAsyncStoragePersister({
    storage: window.localStorage,
  });

  persistQueryClient({
    queryClient,
    persister,
    maxAge: 1000 * 60 * 60 * 24, // 24 hours
    dehydrateOptions: {
      shouldDehydrateQuery: (query: any) => {
        return query?.queryKey[0] === "auth";
      },
    },
  });

  return (
    <PersistQueryClientProvider
      client={queryClient}
      persistOptions={{ persister }}
    >
      <InternalApp />
    </PersistQueryClientProvider>
  );
}
