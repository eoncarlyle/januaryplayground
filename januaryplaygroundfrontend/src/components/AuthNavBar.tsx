import { Button } from "@/components/ui/button";

export default function AuthNavBar() {
  return (
    <nav className="border-b">
      <div className="flex h-16 items-center px-4 w-full">
        <div className="flex items-center space-x-2">
          <span className="text-xl font-semibold">Janurary Playground</span>
        </div>
        <div className="flex-1" />

        <Button
          onClick={() => console.log("clicked")}
          className="flex items-center space-x-2"
        >
          <span> Log out </span>
        </Button>
      </div>
    </nav>
  );
}
