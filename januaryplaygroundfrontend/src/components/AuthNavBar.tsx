import {Button} from "@/components/ui/button";
import {useLocation} from "wouter";
import {useLogout} from "@/util/queries.ts";

export default function AuthNavBar() {
  const [_location, setLocation] = useLocation();
  const { mutate: logout } = useLogout()

  return (
    <nav className="border-b">
      <div className="flex h-16 items-center px-4 w-full">
        <div className="flex items-center space-x-2">
          <span className="text-xl font-semibold">Janurary Playground</span>
        </div>
        <div className="flex-1" />

        <Button
          onClick={() => {
            logout()
            setLocation("/")
          }}
          className="flex items-center space-x-2"
        >
          <span> Log out </span>
        </Button>
      </div>
    </nav>
  );
}
