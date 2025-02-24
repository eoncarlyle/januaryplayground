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

## Feedback Log

### `ac5e8f8`
- The market order matching logic in getMarketOrderProposal could benefit from better organization
-  The error handling could be more granular, could add:
  - Validation errors for order parameters
  - Market condition errors (e.g., circuit breakers)
  - System state errors (e.g., order book inconsistency)
- `OrderPartialFilled` not utilised
- Consider Arrow's `ValidatedNel` class

### `bcedfa7`
> The authentication flow seems to delete the token immediately after successful authentication...
> ...This might cause issues if there's any network instability during the WebSocket connection process. Consider adding a grace period or different token lifecycle management.

### `48e627d`
- Note: diff evaluated from `32686c8` to `48e627d`
- Backend
  - Add validation for WebSocket message payloads
  - Rate limiting on the WebSocket messages, bucketd by tokens
  - More granular error types and more error logging
  - Implement session invalidation
  - Auth endpoint rate limiting
  - WebSocket connection timeout
- Frontend
  - Retry logic for failed auth
  - Add loading spinner during auth
  - WebSocket reconnection logic

### `32686c8`
- Consider sealed class hierarchy for `WebSocketMessage`
- Configuration file for cookie durations
- More verbose error messages
- Consider allowing to authentication retries to the websockets
- Consider token-bucketed rate limiting rather than the naive approach

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

- Inside of `useEffect`, define `onopen`, `onmessage`, `onerror`, `onclose`, and a component cleanup lambda to close any open sockets on unmount
  - Websocket lifecycle means that the nesting in the hook is unnecessary
  - Can `if(socket) return;` at top of `useEffect`

## Active Topic Notes

### Table design
- The columns `user` and `ticker` in the `position` table refer to `email` and `symbol` in the `user` and `ticker` tables
- I might come to regret this, but prices will be reflected as integers at least for now
- Because SQLite does not have a boolean datatype, `open` in `ticker` will need to be `0` or `1`
- Position types (long or short), will be reflected in integer form as well
- This information will all need to live somewhere
- Positions reflect ownership of a ticker, while

### Initial Market Design
- Start with single instrument, long only
- Initially don't support end-user order submission
- Moving pieces
  - One market maker with three naive bots and one more sophisticated trend-following bot, detail in `TrendFollower.md`
  - Market maker
    - Simple spread (2% or so, will need to be careful with small prices/rounding)
    - Cancels existing orders when the spread changes
    - Do not want to inately make the assumption that the one market maker will always be the only market maker
        - Each order book entry placed by a market maker should include time, such that the first matching order is respected
  - Exchange
    - A counterpart order needs to exist and the trader needs to have the funds to run it
    - This needs to check against the database
- ~~Have a tick timing system: not dependent on the wallclock time, but rather dependant on the time relative to the market starting~~
- Client requests originate via WebSockets
  - ~~Not entirely sure how to split up the services~~
    - ~~Client -> WebSocket -> App module -> Kafka -> Market module -> Kafka -> App module -> WebSocket -> Client?~~
- Start without Kafka
  - Possibly never introduce it
  - Reserve for recording trades?
- The arbiter of time is the app module
  - No times sent by the client are to be trusted
- Doing the markets transactions over websockets doesn't make sense, these are a better fit for request/response
- Only market and FOK orders will validate that required balance exists for buys/required shares exist for sales
  - For other order types, validation will happen aysnchronously
  - For asyncrhonous resolution, if resolution criteria met but insufficient balance/shares present then the pending order will be deeleted
- We will need to implement limit orders in order to get the naive market maker running correctly
- Need to destinguish between position type and order types, come back to this
- I don't think that the non-market `OrderTypes` need to include prices given that they will be reflected elsewhere
- A user's portfolio includes both `positions` and `pending_orders`: an order request or order cancelation converts between the two
  - However: only _sell_ orders in `pending_orders` are part of the portfolio 

## Previous Topic Notes

### WebSocket Authentication Detail
- Create an endpoint that returns a short-lived token over JSON for authenticated HTTP contexts
- If legitimate, invalidate the token and then store it in the WebSocket user store
- Send short-lived token with initial WebSocket response on `"auth"` messages
- At `e1fe7dd`, the websocket would only authenticate when logged in for the first time but not when the page was reloaded
  - In `setupWebsocket`, a `console.log(socket.readyState);`

### New authentication
- Run top-level auth
- Change the backend to include when the session will inspire
- Save both in storage and in global state
- Write async job that logs out when the tu
- Write function that logs out (state and cookies) if 403s are hit

### Client-side Websockets
- ~Use socket.io for the client side~: vanilla websockets will work instead
- There are only a handful of routes that this will be relevant for: I we need to decide which components need websockets before putting this into place?
- One possibility is storing effectively a singleton in the application state
  - This would mean creating a WebSocket object in the
- The state built off of the singleton could be organised on the page level
  - The types of
- If it is stored in `useState`, we'll need to make sure that the process of creating websockets can read off of something like local storage or something
  - We need subsequent websockets to pick up where previous ones left off

## Auth Notes
- Session based authentication: storing cookies them for validation
- Could add `userRoles` to the session object as well
- `return@beforematched` and `ctx.skipRemainingHandlers()` important
- Everything are HTTP only cookies, this is handled between server and browser, JS is completely out of the loop
- Session, refresh token possible as well
- Spent almost an hour because cookies aren't saved unless explicitly allowed
  - Should review the following
  - https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch#including_credentials

## Market Kafka Topics
  - Market open/close
  - Order submission requests (trader, position, volume, time)
  - Order submission results (trader, position, volume, time)
  - Existing order book updates
  - Fringe topics
    - Lifecycle events: traders running out of money or joining market (not instrument specific)
    - Admin actions: granting shares and funds?
