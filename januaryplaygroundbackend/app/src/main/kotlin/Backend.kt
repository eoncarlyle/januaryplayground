import arrow.core.Either
import com.fasterxml.jackson.databind.ObjectMapper
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.bodyAsClass
import io.javalin.http.util.NaiveRateLimit
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

private data class QuoteQueueMessage<T>(
    val request: T,
    val initialQuote: Quote?,
    val finalQuote: Quote?
)

class Backend(db: DatabaseHelper, secure: Boolean) {
    private val wsUserMap = WsUserMap()
    private val logger by lazy { LoggerFactory.getLogger(Backend::class.java) }
    private val quoteQueue = LinkedBlockingQueue<QuoteQueueMessage<Any>>()
    private val objectMapper = ObjectMapper()
    private val readWriteSemaphore = Semaphore(1)
    private val readerLightswitch = Lightswitch(readWriteSemaphore)

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
    private val marketService = ExchangeService(db, secure, wsUserMap, logger)

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
            if (marketService.validateTicker(ticker)) {
                val quote = marketService.getQuote(ticker, readerLightswitch)
                if (quote != null) {
                    ctx.status(200)
                    ctx.json(quote)
                } else {
                    ctx.status(500)
                    ctx.json("message" to "Unknown error with '$ticker'")
                }
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/positions") { ctx ->
            val dto = ctx.bodyAsClass<ExchangeRequestDto>();
            val ticker = dto.ticker
            if (marketService.validateTicker(ticker)) {
                ctx.status(200)
                ctx.json(marketService.getUserLongPositions(dto.email, dto.ticker, readerLightswitch))
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/orders") { ctx ->
            val dto = ctx.bodyAsClass<ExchangeRequestDto>();
            val ticker = dto.ticker
            if (marketService.validateTicker(ticker)) {
                ctx.status(200)
                ctx.json(marketService.getUserOrders(dto.email, dto.ticker, readerLightswitch))
            } else {
                ctx.status(404)
                ctx.json("message" to "Unknown ticker '$ticker' during order semaphore acquisition")
            }
        }

        this.javalinApp.post("/exchange/orders/market") { ctx ->
            val orderRequest = ctx.bodyAsClass<MarketOrderRequest>()
            logger.info(objectMapper.writeValueAsString(orderRequest))
            logger.info("Starting state: {}", marketService.getState().toString())
            if (marketService.validateTicker(orderRequest.ticker)) {
                val initialQuote = marketService.getWriteQuote(orderRequest.ticker)
                marketService.marketOrderRequest(orderRequest, readWriteSemaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        logger.info("Final state: {}", marketService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                orderRequest,
                                initialQuote,
                                marketService.getWriteQuote(orderRequest.ticker)
                            )
                        )
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
            logger.info(objectMapper.writeValueAsString(orderRequest))
            logger.info("Starting state: {}", marketService.getState().toString())
            // Use optionals to unnest
            if (marketService.validateTicker(orderRequest.ticker)) {
                val initialQuote = marketService.getWriteQuote(orderRequest.ticker)
                marketService.limitOrderRequest(orderRequest, readWriteSemaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        logger.info("Final state: {}", marketService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                orderRequest,
                                initialQuote,
                                marketService.getWriteQuote(orderRequest.ticker)
                            )
                        )
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
            logger.info("Starting quote: {}", marketService.getState().toString())
            // Use optionals to unnest
            if (marketService.validateTicker(cancelRequest.ticker)) {
                val initialQuote = marketService.getWriteQuote(cancelRequest.ticker)
                marketService.allOrderCancel(cancelRequest, readWriteSemaphore)
                    .onRight { response ->
                        ctx.status(201)
                        ctx.json(response)
                        logger.info("Final quote: {}", marketService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                cancelRequest,
                                initialQuote,
                                marketService.getWriteQuote(cancelRequest.ticker)
                            )
                        )
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

    private fun startServerEventSimulation() {
        Thread {
            while (true) {
                Thread.sleep(5000) // Every 5 seconds

                wsUserMap.forEachLiveSocket { ctx ->
                    ctx.send(objectMapper.writeValueAsString(ServerTimeMessage(System.currentTimeMillis())))
                }

            }
        }.start()
    }

    private fun orderQuoteConsumer() {
        Thread {
            while (true) {
                val (request, initialQuote, finalQuote) = quoteQueue.take()

                if (initialQuote != null && finalQuote != null) {
                    if (initialQuote.ask != finalQuote.ask || initialQuote.bid != finalQuote.bid) {
                        logger.info("Quote Update")
                        logger.info("Initial Quote: {}", initialQuote.toString())
                        logger.info("Final Quote: {}", finalQuote.toString())
                        logger.info(request.toString())

                        val liveSockets = wsUserMap.forEachLiveSocket { ctx ->
                            ctx.send(QuoteMessage(finalQuote))
                        }
                        logger.info("Updated ${liveSockets} clients over websockets with new quote");
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
        }.start()
    }
}
