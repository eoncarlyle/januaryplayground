import { useLocation } from "wouter";

import { AuthProps } from "../model";
import { useAuthRedirect } from "../util/rest";
import AuthNavBar from "./AuthNavBar";
import Layout from "./Layout";

export default function Home(authPops: AuthProps) {
  const [_location, setLocation] = useLocation();

  // Check auth if we know it is wrong
  useAuthRedirect(true, setLocation, authPops);

  return (
    <Layout>
      <>
        <AuthNavBar
          authState={authPops.authState}
          setAuthState={authPops.setAuthState}
        />
        <div className="m-2 flex justify-center">
          {authPops.authState.email}
        </div>
      </>
    </Layout>
  );
}
