package com.iainschmitt.januaryplaygroundbackend.app

import io.javalin.Javalin
import io.javalin.http.util.NaiveRateLimit
import io.javalin.websocket.WsContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
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

    val app = App(db, secure)
    app.run()
}

class App(db: DatabaseHelper, secure: Boolean) {
    private val wsUserMap: WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>()
    private val logger by lazy { LoggerFactory.getLogger(App::class.java) }

    private val javalinApp = Javalin.create({ config ->
        config.bundledPlugins.enableCors { cors ->
            // TODO: specify this correctly in production
            cors.addRule {
                it.allowHost("http://localhost:5173")
                it.allowCredentials = true
            }
        }
        config.requestLogger.http { ctx, ms ->
            logger.info(
                "{} {} {} took {} ms",
                ctx.method(),
                ctx.path(),
                ctx.status(),
                ms
            )
        }
    })
    private val auth = Auth(db, secure, wsUserMap, logger)

    fun run() {
        this.javalinApp.get("/health") { ctx -> ctx.result("Up") }
        this.javalinApp.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
        this.javalinApp.post("/auth/signup") { ctx -> auth.signUpHandler(ctx) }
        this.javalinApp.post("/auth/login") { ctx -> auth.logInHandler(ctx) }
        this.javalinApp.get("/auth/evaluate") { ctx -> auth.evaluateAuthHandler(ctx) }
        this.javalinApp.post("/auth/logout") { ctx -> auth.logOut(ctx) }
        this.javalinApp.post("/auth/sessions/temporary") { ctx -> auth.temporarySessionHttpHandler(ctx) }

        this.javalinApp.ws("/ws") { ws ->
            ws.onConnect { ctx -> auth.handleWsConnection(ctx) }
            ws.onMessage { ctx ->
                try {
                    val message = ctx.messageAsClass<WebSocketMessage>()
                    when (message) {
                        is SocketLifecycleMessage -> auth.handleWsAuth(ctx, message)
                    }
                } catch (e: Exception) {
                    logger.error("Unable to serialise '{}'", ctx.message())
                    ctx.send(auth.wsResponse(WebSocketStatus.ERROR, "internal server error"))
                }
            }
            ws.onClose { ctx ->
                logger.info("Closing WebSocket connection")
                try {
                    auth.handleWsClose(ctx)
                } catch (e: IOException) {
                    logger.warn("Exception-throwing close")
                }
            }
        }
        this.javalinApp.start(7070)

        startServerEventSimulation()
    }


    private fun startServerEventSimulation() {
        Thread {
            while (true) {
                Thread.sleep(5000) // Every 5 seconds
                val serverUpdate = "Server time: ${System.currentTimeMillis()}"
                val aliveSockets = wsUserMap.keys.filter { it.session.isOpen && wsUserMap[it]?.authenticated ?: false }
                logger.info("Sending event to {} websockets", aliveSockets.size)
                aliveSockets.forEach { session ->
                    logger.info("Sending message to {}", session.toString())
                    session.send(serverUpdate)
                }
            }
        }
            .start()
    }
}
