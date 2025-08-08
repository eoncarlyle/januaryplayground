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


## Active Topic Notes

### To Do
- TODO
  - Remove requirement of email to auth evaluation
  - New mutation hooks
  - New query hooks
  - Remove App.tsx manual state
  - Change AuthProps
  - Downstream component changes
  - Persistence addition
- Prevent prices of -1 in market transactions
- Faster position cleanup: include information about current position in the order proposal, checking for deletes each time is not good
- ~~The market makers need to be better capitalised: if they run out of positions to sell that is a pretty big problem~~
  - Notification service could alert
- Test multi-ticker, multi-market maker activity
- Actual spread narrowing by the market maker and noise trader activity
  - Measure based off of volitility
- Noise trader lifecyle (detail above)
- Fix domain model to turn order types into buy/sell/short
- Observation frontend
- Client order submission frontend

### Reference

Rollback command that I keep forgetting
```shell
$ sqlite3 backup-app.sqlite ".backup app.sqlite"
```

If needed, Kafka consumer deserialisation, where the value of `type` is the descriminator value
```kotlin
@Serializable
sealed class Event {
    @Serializable
    @SerialName("my_first_descriminator_value")
    data class FirstSubclass(val a: String, val b: Boolean) : Event()

    @Serializable
    @SerialName("my_second_descriminator_value")
    data class SecondSubclass(val a: String, val b: Boolean) : Event()
}
```

## Previous Topic Notes

### Notification and Orchestrator Design
Clients can be treated as external services: with a valid password they can sign up, and they can use the resulting client cookies to make REST requests; WebSocket auth is slightly more complicated but follows similar logic. There's no stopping someone from making their own market maker and having it be treated the same way. However, I want to be able to continually operate the market maker and noise traders without stopping anything. This should also be a relatively durable process, so there should be more than an in-flight network request making things work here. Because even my simple market maker outperforms randome noise traders, continual activity requires 'restocking' noise traders that run out of credits.

The flow works something like this
1) The orchestrator starts running a handful of noise traders
2) The market maker sends a request to the backend to notify it when it has over a certain number of credits
3) The market maker has the option to place funds into the orchestrator account with a REST call
4) While communication between clients and backend is carried out over WebSockets, communication between internal services will use Kafka, which I have recently setup with SSL
5) The backend is a producer on the client-orchestration topic, and it will send an event on this topic ordering another
6) The orchestrator consumes these events, and creates a new noise trader on demand
7) The orchestartor should have the power to liquidate nosie traders that it creates

The resulting requirements will be
- [x] Budget event notification endpoint: simple at first, just under or over a budget limit
    - [x] Market maker event notificaiton handling, including dismisal
- [x] Credit transfer endpoint
- [x] Client orchestrator service
    - [x] Backend: emit on transfer to orchestrator
    - [x] Orchestrator
        - [x] Consume events
        - [x] Liquidate all orchestrated accounts on startup
        - [x] Sign up noise trader, start noise trader
        - [x] Liquidate when NoiseTrader `main` returns
        - [x] Provide callback to remove the noise trader from the set of known live `OrchestratedNoiseTraders`
- [x] Adding an 'orchestrated_by' column in the database
- [x] Adding an orchestrated sign-in that ties a given client to an orchestrator
- [x] Liquidation endpoint callable by orchestrators
- [x] Orchestrator handling clients as either completeable futures or coroutine equivalent and calling liquidiation endpoint when they run out, the nrunning
  Current bugs
- [x] Backend notification cache broken
- [x] Orchestrator doesn't use stranded funds on startup
    - Get balance on startup and seed `balance % 150` acounts
    - Use blocking queue to re-start orchestrated accounts that flap out
- [x] Somehow possible to get negative balances
- [x] Prevent the multiple-notification spam: do something similar as was done for the quote staleness
- [x] Prevent 204s on cancel-all from killing market maker


Subsequent things I could do
- [ ] Caching quote, positions, orders, balance

```text
22:02:20.911 [DefaultDispatcher-worker-14] INFO MainKt -- --------Incoming Quote---------
22:02:20.912 [DefaultDispatcher-worker-14] INFO MainKt -- Current quote: testTicker: 32/37 @ 1753758131846
22:02:20.912 [DefaultDispatcher-worker-14] INFO MainKt -- Incoming quote: testTicker: 32/-1 @ 1753758140899
22:02:20.932 [DefaultDispatcher-worker-14] INFO MainKt -- Next quote: testTicker: 33/38 @ 1753758140932
22:02:20.974 [DefaultDispatcher-worker-15] INFO MainKt -- Notification sent to market maker
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- Credit exceed notification and transfer to orchestrator@iainschmitt.com
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- --------Incoming Quote---------
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- Current quote: testTicker: 33/38 @ 1753758140932
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- Incoming quote: testTicker: 30/-1 @ 1753758140928
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- Ignoring stale incoming quote from 1753758140928, tracking quote set at 1753758140953
22:02:21.006 [DefaultDispatcher-worker-1] INFO MainKt -- Notification sent to market maker
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- Credit exceed notification and transfer to orchestrator@iainschmitt.com
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- --------Incoming Quote---------
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- Current quote: testTicker: 33/38 @ 1753758140932
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- Incoming quote: testTicker: 33/-1 @ 1753758140937
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- Ignoring stale incoming quote from 1753758140937, tracking quote set at 1753758140953
22:02:21.015 [DefaultDispatcher-worker-15] INFO MainKt -- Notification sent to market maker
22:02:21.022 [DefaultDispatcher-worker-7] INFO MainKt -- Credit exceed notification and transfer to orchestrator@iainschmitt.com
22:02:21.023 [DefaultDispatcher-worker-7] INFO MainKt -- --------Incoming Quote---------
22:02:21.023 [DefaultDispatcher-worker-7] INFO MainKt -- Current quote: testTicker: 33/38 @ 1753758140932
22:02:21.023 [DefaultDispatcher-worker-7] INFO MainKt -- Incoming quote: testTicker: 33/38 @ 1753758140953
22:02:21.023 [DefaultDispatcher-worker-7] INFO MainKt -- Ignoring stale incoming quote from 1753758140953, tracking quote set at 1753758140953
22:02:21.023 [DefaultDispatcher-worker-7] INFO MainKt -- Notification sent to market maker
22:02:21.030 [DefaultDispatcher-worker-11] INFO MainKt -- Credit exceed notification and transfer to orchestrator@iainschmitt.com
```

### WebSocket Authentication Detail
- Create an endpoint that returns a short-lived token over JSON for authenticated HTTP contexts
- If legitimate, invalidate the token and then store it in the WebSocket user store
- Send short-lived token with initial WebSocket response on `"auth"` messages
- At `e1fe7dd`, the websocket would only authenticate when logged in for the first time but not when the page was reloaded
  - In `setupWebsocket`, a `console.log(socket.readyState);`

### New authentication
- Run top-level auth
- Change the backend to include when the session will expire
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

### Auth Notes
- Session based authentication: storing cookies them for validation
- Could add `userRoles` to the session object as well
- `return@beforematched` and `ctx.skipRemainingHandlers()` important
- Everything are HTTP only cookies, this is handled between server and browser, JS is completely out of the loop
- Session, refresh token possible as well
- Spent almost an hour because cookies aren't saved unless explicitly allowed
  - Should review the following
  - https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch#including_credentials

### Market Kafka Topics
  - Market open/close
  - Order submission requests (trader, position, volume, time)
  - Order submission results (trader, position, volume, time)
  - Existing order book updates
  - Fringe topics
    - Lifecycle events: traders running out of money or joining market (not instrument specific)
    - Admin action

### Feedback Log

#### `00f3ae4`
The coroutine usage wasn't really working

#### `29c9d78`

The market maker operates like:
1) Market makers submit limit buy and sell orders at a given spread
2) When the market spread changes, all orders are canceled and new limit orders are submitted at the updated spread.
- Current issue with the market maker is that canceling orders the quote itself
- This breaks "quote change → submit new orders → new market maker quote" cycle midway through
- This should be fixed with a semaphore that prevents `onQuote`


#### `edd10ca`
- Last two steps before testing are
  - Large scale order cancelation
  - Refactoring to more rational types (sort inbound/outbound, make the model vs. http model make more sense)
  - WebSocket notification of prices

#### `ac5e8f8`
- The market order matching logic in getMarketOrderProposal could benefit from better organization
-  The error handling could be more granular, could add:
  - Validation errors for order parameters
  - Market condition errors (e.g., circuit breakers)
  - System state errors (e.g., order book inconsistency)
- `OrderPartialFilled` not utilised
- Consider Arrow's `ValidatedNel` class

#### `bcedfa7`
> The authentication flow seems to delete the token immediately after successful authentication...
> ...This might cause issues if there's any network instability during the WebSocket connection process. Consider adding a grace period or different token lifecycle management.

#### `48e627d`
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

#### `32686c8`
- Consider sealed class hierarchy for `WebSocketMessage`
- Configuration file for cookie durations
- More verbose error messages
- Consider allowing to authentication retries to the websockets
- Consider token-bucketed rate limiting rather than the naive approach

#### `c03ce94`
- Should handle network errors separately from auth failures (explicit 403 handing in auth)
- Add session invalidation on logout
- Implement proper session cleanup for expired sessions
- The auth feedback mechanism needs completion

#### `39c923e`
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

#### `cd9bc43`
- ~Add rate-limiting for auth endpoints~
- Reduce duplicate code in `LogIn` and `SignUp`
- Consider a `useAuthForm` for shared auth work
- Consider CSRF protection

#### `41afb33`
- Database security
  - Hide databse errors (change HTTP error based off of command-line security flag)
  - Harden SQL statements against injection
- Session management
  - Implement expired session cleanup

#### `2996280`
- ~Change how passwords are handled~
  - ~Send raw password over HTTPS~
  - ~Hash server-side with unique salt: `val hashedPassword = BCrypt.hashpw(rawPassword, BCrypt.gensalt())`~
  - ~Store the salt alongside the user credentials in the database~
~- Use `POST` method on anything auth related~
- Make sure to add auth to WebSocket communication
- If you were using anything other than SQLite, use a database thread pool
- Add input validation to DB queries

#### `62e5324`
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

### Putting the pieces together

- Market order responses are not deserialised
- ~~Partial fills do not update orders~~
  - I don't love using the `where` statement
  - If there are any issues with that side of the code then an order id should be tied to a position
  - We only need to match orders to their positions: position without an order not issue for initialisation
- ~~A 'null' result on `(select max(price) from order_records where ticker = 'testTicker' and trade_type = 0 and filled_tick = -1) as bid` is interpreted as zero, must change quote~~
  - ~~A `-1` price should reflect no bids or asks, should default to shifting the market upwards~~
  - If both bids, asks exhausted then widen the market
  - If only one of bid or ask has been exhausted and the market has widened, then the market can be narrowed
- New rule about the market: positions are coalesced, so if a trader has a two long positions on `myTestTicker` and they buy two more, the position will be updated rather than a new position logged
  - This will require a unique `unique(user, ticker, position_type)` but this seems like a small price to pay
  - The advantage to this is that only one row needs to be updated to keep positions updated
  - Related 'There really only should be _one_ of these \[partial order\] ever run'
    - This should be true but it doesn't imply that complete orders will zero out a position

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
- All orders will need to have time recorded in order to respect FIFO ordering
- Something that dramatically simplifies the market design is by treating cash in the exact same way as shares for limit orders
  - Once something is a pending order, is committed: credits comitted to a limit order are tied up in a limit order until the order is filled or cancelled
  - This isn't the most realistic treatment, but it simplifies some things for me for now

### Implementing Kotlin Clients
Not using suspend in the server only made sense because I was using Javalin - I am sure there is some equivalent out there, but I wanted to get up and running and it didn't seem like that much of an impediment. As of now, there will only be two kinds of clients: market makers and noise traders (not that it has a large bearing on the general structure of how clients work).

On client startup both HTTP and websocket auth need to take place. When websocket events are sent then things will have to be actioned on immediately, but there are also heartbeat style and other timed tasks to work with. It looks like the way forward is several coroutines started with the `lauch` keyword: one responds to websockets, one does 'heartbeat' tasks, and one carries out long-running tasks for things like re-authenticating: credentials. Before moving forward on this it probably makes sense to do some actual reading on how coroutines in Kotlin work -

Test market maker credentials
- email: `testmm@iainschmitt.com`
- password: `myTestMmPassword`

Test noise trader credentials
- email: `noise0@iainschmitt.com`
- password: `noisePassword`

`AuthClient.kt` re-written to `BackendClient.kt`.
- If the market maker consistently beats the noise traders could hit endpoint to spawn new noise trader
- A noise trader spawn task could be listening to launch new noise traders in new coroutines
- Kafka is simply a much better way to handle quotes than websockets
- Should use lightswitch semaphore pattern for tickers

Writing new websocket messages types has made me realise two things
- The `type` field in any HTTP DTO isn't neccessary and should be removed
- Any usage of 'incoming' and 'outgoing' generally don't make sense
  - HTTP messages should be thought of in request/response
  - WebSocket messages should be thought of in client/server
- The only client messages sent to the websocket server is incoming auth, which simplifies some things
- Right now the client return calls are a little all over the place - a `Pair<Int, String>` makes a lot of sense, but not everything else does
- Market maker concerns
  - If no positions, can't be a market maker (can't sell what you don't have),
  - This can be manual for now, but later on there should be some endpoint that initialises market makers by ticker
  - This should probably be different than initialisation, as the operation to initialise is by ticker
