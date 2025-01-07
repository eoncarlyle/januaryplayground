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
- Session based authentication: still sending JWTs, but you're also storing them for validation
- Could add `userRoles` to the session object as well
- `return@beforematched` and `ctx.skipRemainingHandlers()` important
- Everything are HTTP only cookies, this is handled between server and browser, JS is completely out of the loop
- Session, refresh token possible as well

## Feedback Log
### `62e5324`
- Use atomic integer for `usercount`
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
