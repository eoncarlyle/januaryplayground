# README

## Purpose
In Janurary 2025 I wanted to find an excuse to work with the following technologies
- Javalin
- Kotlin
- WebSockets
- Shadcn Component Library
- Apache Kafka
- Session-based auth (https://github.com/stolinski/drop-in)

This repository is a playground for working on a grab bag of these technologies, I probably won't find a way to shoehorn all of these in

## Auth Notes
- Session based authentication: storing cookies them for validation
- Could add `userRoles` to the session object as well
- `return@beforematched` and `ctx.skipRemainingHandlers()` important
- Everything are HTTP only cookies, this is handled between server and browser, JS is completely out of the loop
- Session, refresh token possible as well
- Spent almost an hour because cookies aren't saved unless explicitly allowed
  - Should review the following
  - https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch#including_credentials

## Feedback Log

### `c03ce94`
- Should handle network errors separately from auth failures (explicit 403 handing in auth)
- Add session invalidation on logout
- Implement proper session cleanup for expired sessions
- The auth feedback mechanism needs completion

### `39c923e`
- Consider adding fallback/safety checks for localStorage availability
- Might want to add error boundaries for cases where localStorage is disabled
- Auth State
  - The email could be null but there's no explicit handling of this case
  - Consider adding validation for the email value
- Constants
  - Consider moving these to a dedicated constants file
  - Add a prefix to avoid potential naming conflicts (e.g., `AUTH_EMAIL`)
- Consider delay between `setAuth`, `redirectOnSuccess`
- Consider loading state during form submission

### `cd9bc43`
- ~Add rate-limiting for auth endpoints~
- Reduce duplicate code in `LogIn` and `SignUp`
- Consider a `useAuthForm` for shared auth work
- Consider CSRF protection

### `41afb33`
- Database security
  - Hide databse errors (change HTTP error based off of command-line security flag)
  - Harden SQL statements against injection
- Session management
  - Implement expired session cleanup

### `2996280`
- ~Change how passwords are handled~
  - ~Send raw password over HTTPS~
  - ~Hash server-side with unique salt: `val hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt())`~
  - ~Store the salt alongside the user credentials in the database~
~- Use `POST` method on anything auth related~
- Make sure to add auth to WebSocket communication
- If you were using anything other than SQLite, use a database thread pool
- Add input validation to DB queries

### `62e5324`
- ~Use atomic integer for `usercount`~
- Change `startServerEventSimulation` to be able to stop thread, use coroutine as welll

```kotlin
private fun startServerEventSimulation() {
    // Consider using a CoroutineScope instead
    val scope = CoroutineScope(Dispatchers.Default)
    scope.launch {
        while (isActive) {  // Respect cancellation
            delay(5000)  // Non-blocking delay
            val serverUpdate = "Server time: ${System.currentTimeMillis()}"
            userMap.keys.filter { it.session.isOpen }
                .forEach { session ->
                    session.send(serverUpdate)
                }
        }
    }
}
```

- Inside of `useEffect`, define `onopen`, `onmessage`, `onerror`, `oneclose`, and a component cleanup lambda to close any open sockets on unmount
  - Websocket lifecycle means that the nesting in the hook is unnecessary
  - Can `if(socket) return;` at top of `useEffect`

## WebSocket Authentication Detail
- Create an endpoint that returns a short-lived token over JSON for authenticated HTTP contexts
- If legitimate, invalidate the token and then store it in the WebSocket user store
- Send short-lived token with initial WebSocket response on `"auth"` messages

## New authentication
- Run top-level auth
- Change the backend to include when the session will inspire
- Save both in storage and in global state
- Write async job that logs out when the tu
- Write function that logs out (state and cookies) if 403s are hit
