import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
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
import { useAuth, useLogin } from "@/util/queries";
import { useAuthRedirect } from "@/util/rest";
import { zodResolver } from "@hookform/resolvers/zod/src/zod.js";
import { useForm } from "react-hook-form";
import { useLocation } from "wouter";
import { z } from "zod";
import { useEffect } from "react";

const logInSchema = z.object({
  email: z
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
      email: "user2@mail.com",
      password: "User2Password",
    },
  });

  const [location, setLocation] = useLocation();
  const { data: authData } = useAuth();
  const { mutate: login } = useLogin();
  
  useEffect(() => {
    useAuthRedirect(false, authData, location, setLocation);
  }, [authData, location, setLocation]);
  
  return (
    //<Card className="w-full max-w-md">
    <Card>
      <Form {...form}>
        <CardHeader className="space-y-1">
          <CardTitle className="text-2xl">Log In</CardTitle>
        </CardHeader>

        <form
          className="space-y-6"
          onSubmit={form.handleSubmit((data: LogInValues) => {
            login(data);
          })}
        >
          {form.formState.errors.root && (
            <div className="text-red-500 text-sm">
              {form.formState.errors.root.message}
            </div>
          )}

          <CardContent className="grid gap-4">
            <FormField
              control={form.control}
              name="email"
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
