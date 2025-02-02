package com.iainschmitt.januaryplaygroundbackend

import io.javalin.Javalin
import io.javalin.http.util.NaiveRateLimit
import io.javalin.websocket.WsContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val wsUserMap: WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>()

fun main(vararg args: String) {
    if (args.size < 2) {
        throw IllegalArgumentException("Empty args")
    }
    val db = DatabaseHelper(args[0])

    val secure =
        when (args[1]) {
            "insecure" -> false
            "secure" -> true
            else -> throw IllegalArgumentException("Invalid `cookieSecure`")
        }

    val app = Javalin.create({ config ->
        config.bundledPlugins.enableCors { cors ->
            // TODO: specify this correctly in production
            cors.addRule {
                it.allowHost("http://localhost:5173")
                it.allowCredentials = true
            }
        }
    }).start(7070)

    val auth = Auth(db, secure, wsUserMap)
    app.get("/health") { ctx -> ctx.result("Up") }
    app.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
    app.post("/auth/signup") { ctx -> auth.signUpHandler(ctx) }
    app.post("/auth/login") { ctx -> auth.logInHandler(ctx) }
    app.get("/auth/evaluate") { ctx -> auth.evaluateAuthHandler(ctx) }
    app.post("/auth/logout") { ctx -> auth.logOut(ctx) }
    app.post("/auth/sessions/temporary") { ctx -> auth.temporarySessionHttpHandler(ctx) }

    app.ws("/ws") { ws ->
        ws.onConnect { ctx -> auth.handleWsConnection(ctx) }
        ws.onMessage { ctx ->
            try {
                val message = ctx.messageAsClass<WebSocketMessage>()
                when (message) {
                    is AuthWsMessage -> auth.handleWsAuth(ctx, message)
                }
            } catch (e: Exception) {
                ctx.send(auth.wsResponse(WebSocketStatus.ERROR, "internal server error"))
            }
        }
        ws.onClose { ctx ->
            auth.handleWsClose(ctx)
        }
    }
    // startServerEventSimulation()
}

//private fun startServerEventSimulation() {
//    Thread {
//        while (true) {
//            Thread.sleep(5000) // Every 5 seconds
//            val serverUpdate = "Server time: ${System.currentTimeMillis()}"
//            println("Starting server send process")
//            userMap.keys.filter { it.session.isOpen }.forEach { session ->
//                session.send(serverUpdate)
//            }
//        }
//    }
//        .start()
//}
