import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Form,
  FormControl,
  FormField,
  FormItem,
  FormLabel,
  FormMessage,
} from "@/components/ui/form";
import { Input } from "@/components/ui/input";
import { getBaseUrl } from "@/util/rest";
import { zodResolver } from "@hookform/resolvers/zod/src/zod.js";
import { useForm } from "react-hook-form";
import { useLocation } from "wouter";
import { z } from "zod";

const logInSchema = z.object({
  username: z
    .string()
    .min(1, { message: "Email is required" })
    .email({ message: "Must be a valid email address" }),
  password: z
    .string()
    .min(1, { message: "Password must be required" })
    .max(64, { message: "Password must be less than 64 characters" }),
});

type LogInValues = z.infer<typeof logInSchema>;

export default function LogIn() {
  const form = useForm<LogInValues>({
    resolver: zodResolver(logInSchema),
    defaultValues: {
      username: "",
      password: "",
    },
  });

  const [_, setLocation] = useLocation();

  //TODO: need to prevent logged in user from accessing this, need a lightweight auth endpoint for this

  const onSubmit = async (data: LogInValues) => {
    try {
      const result = await fetch(`${getBaseUrl()}/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        credentials: "include",
        body: JSON.stringify(data),
      });

      if (result.ok) {
        setLocation("/home");
      } else {
        const errorMessage: unknown = await result.text();

        if (
          result.status < 500 &&
          errorMessage &&
          typeof errorMessage === "string"
        ) {
          form.setError("root", {
            type: "server",
            message: errorMessage,
          });
        } else {
          form.setError("root", {
            type: "server",
            message: "Something went wrong",
          });
        }
      }
    } catch (e: unknown) {
      console.error(`Fetch failed: ${e}`);
    }
  };

  return (
    <Card className="w-full max-w-md">
      <Form {...form}>
        <CardHeader className="space-y-1">
          <CardTitle className="text-2xl">Log In</CardTitle>
        </CardHeader>

        <form onSubmit={form.handleSubmit(onSubmit)} className="space-y-6">
          {form.formState.errors.root && (
            <div className="text-red-500 text-sm">
              {form.formState.errors.root.message}
            </div>
          )}

          <CardContent className="grid gap-4">
            <FormField
              control={form.control}
              name="username"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Email</FormLabel>
                  <FormControl>
                    <Input type="email" placeholder="my@email.com" {...field} />
                  </FormControl>
                  <FormMessage />
                </FormItem>
              )}
            />

            <FormField
              control={form.control}
              name="password"
              render={({ field }) => (
                <FormItem>
                  <FormLabel>Password</FormLabel>
                  <FormControl>
                    <Input type="password" {...field} />
                  </FormControl>
                  <FormMessage className="break-after-all max-w-xs" />
                </FormItem>
              )}
            />
          </CardContent>

          <CardFooter>
            <Button type="submit" className="w-full">
              Log In
            </Button>
          </CardFooter>
        </form>
      </Form>
    </Card>
  );
}
