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
import { AuthProps } from "@/model";
import { createAuthOnSubmitHandler, useAuthRedirect } from "@/util/rest";
import { zodResolver } from "@hookform/resolvers/zod";
import { useForm } from "react-hook-form";
import { useLocation } from "wouter";
import { z } from "zod";

const signUpSchema = z.object({
  email: z
    .string()
    .min(1, { message: "Email is required" })
    .email({ message: "Must be a valid email address" }),
  password: z
    .string()
    .min(8, { message: "Password must be at least 8 characters" })
    .max(64, { message: "Password must be less than 64 characters" })
    .regex(/^(?=.*[a-z])(?=.*[A-Z])/, {
      message: "Password must contain upper and lowercase letters",
    }),
});

type SignUpValues = z.infer<typeof signUpSchema>;

export default function SignUp(authProps: AuthProps) {
  const form = useForm<SignUpValues>({
    resolver: zodResolver(signUpSchema),
    defaultValues: {
      email: "",
      password: "",
    },
  });
  const [_location, setLocation] = useLocation();
  useAuthRedirect(false, setLocation, authProps);

  //TODO: need to prevent logged in user from accessing this, need a lightweight auth endpoint for this

  return (
    <Card className="w-full max-w-md">
      <Form {...form}>
        <CardHeader className="space-y-1">
          <CardTitle className="text-2xl">Sign Up</CardTitle>
          <CardDescription>Enter your email below to sign up</CardDescription>
        </CardHeader>

        <form
          onSubmit={form.handleSubmit(
            createAuthOnSubmitHandler(
              form,
              authProps.setAuthState,
              () => setLocation("/home"),
              "signup",
            ),
          )}
          className="space-y-6"
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
              Sign Up
            </Button>
          </CardFooter>
        </form>
      </Form>
    </Card>
  );
}
