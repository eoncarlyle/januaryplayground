package com.iainschmitt.januaryplaygroundbackend.app

import io.javalin.Javalin
import io.javalin.http.util.NaiveRateLimit
import io.javalin.websocket.WsContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
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
    private val transactionSemaphore: Semaphore = Semaphore(1);

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
    private val authService = AuthService(db, secure, wsUserMap, logger)

    fun run() {
        this.javalinApp.get("/health") { ctx -> ctx.result("Up") }
        this.javalinApp.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
        this.javalinApp.post("/auth/signup") { ctx -> authService.signUp(ctx) }
        this.javalinApp.post("/auth/login") { ctx -> authService.logIn(ctx) }
        this.javalinApp.get("/auth/evaluate") { ctx -> authService.evaluateAuth(ctx) }
        this.javalinApp.post("/auth/logout") { ctx -> authService.logOut(ctx) }
        this.javalinApp.post("/auth/sessions/temporary") { ctx -> authService.temporarySession(ctx) }

        this.javalinApp.beforeMatched("/market") { ctx -> authService.evaluateAuth(ctx)}
        // Note about market orders: they need to be ordered by received time in order to be treated correctly

        this.javalinApp.ws("/ws") { ws ->
            ws.onConnect { ctx -> authService.handleWsConnection(ctx) }
            ws.onMessage { ctx ->
                try {
                    val message = ctx.messageAsClass<WebSocketMessage>()
                    when (message) {
                        is IncomingSocketLifecycleMessage -> authService.handleWsLifecycleMessage(ctx, message)
                    }
                } catch (e: Exception) {
                    logger.error("Unable to serialise '{}'", ctx.message())
                    ctx.sendAsClass(OutgoingError(WebSocketResponseStatus.ERROR, null, "Internal server error"))
                }
            }
            ws.onClose { ctx ->
                logger.info("Closing WebSocket connection")
                try {
                    authService.handleWsClose(ctx, null)
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
