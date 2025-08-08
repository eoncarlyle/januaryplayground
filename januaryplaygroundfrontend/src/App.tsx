import {Route, Switch} from "wouter";
import {persistQueryClient} from '@tanstack/react-query-persist-client-core'
import {createSyncStoragePersister} from '@tanstack/query-sync-storage-persister'

import "./App.css";
import Home from "./components/Home";
import LogIn from "./components/LogIn";
import SignUp from "./components/SignUp";
import {QueryClient, QueryClientProvider} from "@tanstack/react-query";

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
      <Route path="/signup" component={() => <SignUp />}/>
      <Route path="/login" component={() => <LogIn  />}/>
      <Route path="/" component={() => "Landing Page"}/>
      <Route path="/home" component={() => <Home />}/>
    </Switch>
  );
}

  //TODO: Fix the query persistence
export default function App() {
  const persister = createSyncStoragePersister({
    storage: window.localStorage,
  })

  const queryClient = new QueryClient();
  persistQueryClient({
    queryClient,
    persister,
    maxAge: 1000 * 60 * 60 * 24, // 24 hours
    dehydrateOptions: {
      shouldDehydrateQuery: (query: any) => {
        // Only persist auth queries
        return query?.queryKey[0] === 'auth'
      }
    }
  })

  return <QueryClientProvider client={queryClient}>
    <InternalApp/>
  </QueryClientProvider>
}
