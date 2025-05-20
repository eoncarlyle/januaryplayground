import arrow.core.Either
import com.fasterxml.jackson.databind.ObjectMapper
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.bodyAsClass
import io.javalin.http.util.NaiveRateLimit
import io.javalin.websocket.WsContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.*


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
    private val transactionSemaphores = TransactionSemaphores(db)
    private val quoteQueue = LinkedBlockingQueue<Quote>()
    private val objectMapper = ObjectMapper()

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
    private val marketService = MarketService(db, secure, wsUserMap, logger)

    private fun orderFailureHandler(ctx: Context, orderFailure: OrderFailure) {
        ctx.json(mapOf("message" to orderFailure.second))
        when (orderFailure.first) {
            OrderFailureCode.INTERNAL_ERROR -> ctx.status(500)
            OrderFailureCode.UNKNOWN_TICKER -> ctx.status(404)
            OrderFailureCode.UNKNOWN_USER -> ctx.status(404)
            else -> ctx.status(400)
        }
    }

    fun run() {
        this.javalinApp.get("/health") { ctx -> ctx.result("Up") }
        this.javalinApp.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
        this.javalinApp.post("/auth/signup") { ctx -> authService.signUp(ctx) }
        this.javalinApp.post("/auth/login") { ctx -> authService.logIn(ctx) }
        this.javalinApp.get("/auth/evaluate") { ctx -> authService.evaluateAuth(ctx) }
        this.javalinApp.post("/auth/logout") { ctx -> authService.logOut(ctx) }
        this.javalinApp.post("/auth/sessions/temporary") { ctx -> authService.temporarySession(ctx) }

        this.javalinApp.beforeMatched("/exchange") { ctx ->
            val email = ctx.bodyAsClass<Map<String, Any>>()["email"] ?: ""
            if (authService.evaluateUserAuth(ctx, email.toString()) == null) {
                ctx.json(mapOf("message" to "Auth for user $email is invalid"))
                ctx.status(403)
            }
        }

        this.javalinApp.post("/exchange/quote") { ctx ->
            val dto = ctx.bodyAsClass<ExchangeRequestDto>();
            val ticker = dto.ticker
            val semaphore = transactionSemaphores.getSemaphore(ticker)
            val quote =  marketService.getQuote(ticker)
            if (semaphore != null && quote != null) {
                ctx.status(200)
                ctx.json(quote)
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/positions") { ctx ->
            val dto = ctx.bodyAsClass<ExchangeRequestDto>();
            val ticker = dto.ticker
            val semaphore = transactionSemaphores.getSemaphore(ticker)
            if (semaphore != null) {
                ctx.status(200)
                ctx.json(marketService.getUserLongPositions(dto.email, dto.ticker))
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/orders") { ctx ->
            val dto = ctx.bodyAsClass<ExchangeRequestDto>();
            val ticker = dto.ticker
            val semaphore = transactionSemaphores.getSemaphore(ticker)
            if (semaphore != null) {
                ctx.status(200)
                ctx.json(marketService.getUserOrders(dto.email, dto.ticker))
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/orders/market") { ctx ->
            val orderRequest = ctx.bodyAsClass<MarketOrderRequest>()
            val initialQuote = marketService.getQuote(orderRequest.ticker)
            val semaphore = transactionSemaphores.getSemaphore(orderRequest.ticker)
            // Use optionals to unnest
            if (semaphore != null) {
                marketService.marketOrderRequest(orderRequest, semaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        quoteQueueProducer(orderRequest, initialQuote, marketService.getQuote(orderRequest.ticker))
                    }
                    .onLeft { orderFailure -> orderFailureHandler(ctx, orderFailure) }
            } else {
                val orderFailure =
                    OrderFailure(
                        OrderFailureCode.UNKNOWN_TICKER,
                        "Unknown ticker '${orderRequest.ticker}' during order semaphore acquisition"
                    )
                orderFailureHandler(ctx, orderFailure)
            }
        }

        this.javalinApp.post("/exchange/orders/limit") { ctx ->
            val orderRequest = ctx.bodyAsClass<LimitOrderRequest>()
            val initialQuote = marketService.getQuote(orderRequest.ticker)
            val semaphore = transactionSemaphores.getSemaphore(orderRequest.ticker)
            // Use optionals to unnest
            if (semaphore != null) {
                marketService.limitOrderRequest(orderRequest, semaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        when (response) {
                            is OrderPartiallyFilled -> quoteQueueProducer(
                                orderRequest,
                                initialQuote,
                                marketService.getQuote(orderRequest.ticker)
                            )

                            is OrderFilled -> quoteQueueProducer(
                                orderRequest,
                                initialQuote,
                                marketService.getQuote(orderRequest.ticker)
                            )
                        }
                    }
                    .onLeft { orderFailure -> orderFailureHandler(ctx, orderFailure) }
            } else {
                val orderFailure =
                    OrderFailure(OrderFailureCode.UNKNOWN_TICKER, "Unknown ticker during order semaphore acquisition")
                orderFailureHandler(ctx, orderFailure)
            }
        }

        this.javalinApp.post("/exchange/orders/cancel_all") { ctx ->
            val cancelRequest = ctx.bodyAsClass<ExchangeRequestDto>()
            val initialQuote = marketService.getQuote(cancelRequest.ticker)
            val semaphore = transactionSemaphores.getSemaphore(cancelRequest.ticker)
            // Use optionals to unnest
            if (semaphore != null) {
                marketService.allOrderCancel(cancelRequest, semaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        quoteQueueProducer(cancelRequest, initialQuote, marketService.getQuote(cancelRequest.ticker))
                    }
                    .onLeft { cancelFailure ->
                        ctx.json(mapOf("message" to cancelFailure.second))
                        when (cancelFailure.first) {
                            AllOrderCancelFailureCode.UNKNOWN_TICKER -> ctx.status(404)
                            else -> ctx.status(400)
                        }
                    }
            } else {
                ctx.json(mapOf("message" to "Unknown ticker ${cancelRequest.ticker}"))
                ctx.status(404)
            }
        }

        // Note about market orders: they need to be ordered by received time in order to be treated correctly
        this.javalinApp.ws("/ws") { ws ->
            ws.onConnect { ctx -> authService.handleWsConnection(ctx) }
            // The only expected inbound messages are for client authentication
            ws.onMessage { ctx ->
                Either.catch { ctx.messageAsClass<ClientLifecycleMessage>() }
                    .onRight { clientLifecycleMessage ->
                        authService.handleWsLifecycleMessage(
                            ctx,
                            clientLifecycleMessage
                        )
                    }
                    .onLeft {
                        logger.error("Unable to serialise '{}'", ctx.message())
                        ctx.sendAsClass(OutgoingError(WebSocketResponseStatus.ERROR, "Internal server error"))
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
        orderQuoteConsumer()
    }

    private fun <T> quoteQueueProducer(request: T, initialQuote: Quote?, finalQuote: Quote?) {
        logger.info(request.toString())
        logger.info(initialQuote.toString())
        logger.info(finalQuote.toString())
        if (initialQuote != null && finalQuote != null) {
            if (initialQuote.ask != finalQuote.ask || initialQuote.bid != finalQuote.bid) {
                quoteQueue.put(finalQuote)
            }
        } else {
            val serialisedRequest = objectMapper.writeValueAsString(request)
            if (initialQuote == null) {
                logger.warn("Failure to receive initial quote for $serialisedRequest")
            }
            if (finalQuote == null) {
                logger.warn("Failure to receive final quote for $serialisedRequest")
            }
        }
    }

    private fun startServerEventSimulation() {
        Thread {
            while (true) {
                Thread.sleep(5000) // Every 5 seconds
                val aliveSockets = wsUserMap.keys.filter { it.session.isOpen && wsUserMap[it]?.authenticated ?: false }
                aliveSockets.forEach { session ->
                    session.send(objectMapper.writeValueAsString(ServerTimeMessage(System.currentTimeMillis())))
                }
            }
        }.start()
    }

    private fun orderQuoteConsumer() {
        Thread {
            while (true) {
                val quote = quoteQueue.take()
                logger.info("Incoming ticker ${quote.ticker} quote for ${quote.bid}/${quote.ask}")
                val aliveSockets = wsUserMap.keys.filter { it.session.isOpen && wsUserMap[it]?.authenticated ?: false }
                aliveSockets.forEach { session ->
                    session.send(QuoteMessage(quote))
                }
                logger.info("Updated ${aliveSockets.size} clients over websockets about new bid");
            }
        }.start()
    }
}
