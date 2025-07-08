import arrow.core.Either
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.http.util.NaiveRateLimit
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

private data class QuoteQueueMessage<T : Queueable>(
    val request: Any,
    val response: T,
    val initialStatelessQuote: StatelessQuote?,
    val finalStatelessQuote: StatelessQuote?
)

class Backend(db: DatabaseHelper, secure: Boolean) {
    private val wsUserMap = WsUserMap()
    private val logger by lazy { LoggerFactory.getLogger(Backend::class.java) }
    private val quoteQueue = LinkedBlockingQueue<QuoteQueueMessage<Queueable>>()
    private val objectMapper = ObjectMapper()
    private val writeSemaphore = Semaphore(1)
    private val readerLightswitch = Lightswitch(writeSemaphore)

    private val javalinApp = Javalin.create { config ->
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
    }

    private val authService = AuthService(db, secure, wsUserMap, logger)
    private val marketService = ExchangeService(db, secure, wsUserMap, logger)

    private fun exchangeFailureHandler(ctx: Context, orderFailure: OrderFailure) {
        ctx.json(mapOf("message" to orderFailure.second))
        when (orderFailure.first) {
            OrderFailureCode.INTERNAL_ERROR -> ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
            OrderFailureCode.UNKNOWN_TICKER -> ctx.status(HttpStatus.NOT_FOUND)
            OrderFailureCode.UNKNOWN_USER -> ctx.status(HttpStatus.NOT_FOUND)
            else -> ctx.status(HttpStatus.BAD_REQUEST)
        }
    }

    fun run() {
        this.javalinApp.get("/health") { ctx -> ctx.result("Up") }
        this.javalinApp.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
        this.javalinApp.post("/auth/signup") { ctx -> authService.signUp(ctx) }
        this.javalinApp.post("/auth/login") { ctx -> authService.logIn(ctx) }
        this.javalinApp.get("/auth/evaluate") { ctx -> authService.evaluateAuthHandler(ctx) }
        this.javalinApp.post("/auth/logout") { ctx -> authService.logOut(ctx) }
        this.javalinApp.post("/auth/sessions/temporary") { ctx -> authService.temporarySession(ctx) }
        this.javalinApp.post("/auth/orchestrator/signup") { ctx -> authService.signUpOrchestrated(ctx, writeSemaphore) }
        this.javalinApp.post("/auth/orchestrator/liquidate") { ctx -> authService.liquidateOrchestratedUser(ctx, writeSemaphore) }
        this.javalinApp.post("/auth/credit-transfer") { ctx -> authService.transferCredits(ctx, writeSemaphore) }

        this.javalinApp.beforeMatched("/exchange") { ctx ->
            val maybeAuth = authService.evaluateAuth(ctx)
            if (maybeAuth.isNone()) {
                ctx.json(mapOf("message" to "Auth for user " + "'${maybeAuth.getOrElse { "" }}' is invalid"))
                ctx.status(HttpStatus.UNAUTHORIZED)
                ctx.skipRemainingHandlers()
            }
        }

        this.javalinApp.post("/exchange/quote") { ctx ->
            //Extra braces below responded in very strange way
            parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
                val ticker = dto.ticker
                if (marketService.validateTicker(ticker)) {
                    val partialQuote = marketService.getStatelessQuoteOutsideLock(ticker, readerLightswitch)
                    if (partialQuote != null) {
                        val quote = partialQuote.getQuote(System.currentTimeMillis())
                        ctx.status(HttpStatus.OK)
                        ctx.json(quote)
                    } else {
                        ctx.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        ctx.json("message" to "Unknown error with '$ticker'")
                    }
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json("message" to ticker.unknownMessage())
                }
            }.mapLeft { pair ->
                ctx.status(pair.first)
                ctx.json(mapOf("message" to pair.second))
            }
        }

        this.javalinApp.post("/exchange/positions") { ctx ->
            parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
                val ticker = dto.ticker
                if (marketService.validateTicker(ticker)) {
                    ctx.status(HttpStatus.OK)
                    ctx.json(marketService.getUserLongPositions(dto.email, dto.ticker, readerLightswitch))
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json("message" to ticker.unknownMessage())
                }
            }
        }

        this.javalinApp.post("/exchange/orders") { ctx ->
            parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
                val ticker = dto.ticker
                if (marketService.validateTicker(ticker)) {
                    ctx.status(HttpStatus.OK)
                    ctx.json(marketService.getUserOrders(dto.email, dto.ticker, readerLightswitch))
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json("message" to ticker.unknownMessage())
                }
            }
        }

        this.javalinApp.post("/exchange/balance") { ctx ->
            parseCtxBody<BalanceRequestDto>(ctx).map { dto ->
                val result = marketService.getUserBalance(dto.userEmail, readerLightswitch)
                if (result != null) {
                    ctx.status(HttpStatus.OK)
                    ctx.json(BalanceResponse(result))
                } else {
                    ctx.status(HttpStatus.NOT_FOUND)
                    ctx.json("message" to "Unknown user '${dto.userEmail}'")
                }
            }
        }

        this.javalinApp.post("/exchange/orders/market") { ctx ->
            parseCtxBody<MarketOrderRequest>(ctx).map { orderRequest ->
                logger.info(objectMapper.writeValueAsString(orderRequest))
                //logger.info("Starting state: {}", marketService.getState().toString())
                if (marketService.validateTicker(orderRequest.ticker)) {
                    val initialQuote = marketService.getStatelessQuoteInLock(orderRequest.ticker)
                    marketService.marketOrderRequest(orderRequest, writeSemaphore)
                        .onRight { response ->
                            ctx.status(HttpStatus.CREATED)
                            ctx.json(response)
                            //logger.info("Final state: {}", marketService.getState().toString())
                            quoteQueue.put(
                                QuoteQueueMessage(
                                    orderRequest,
                                    response,
                                    initialQuote,
                                    marketService.getStatelessQuoteInLock(orderRequest.ticker)
                                )
                            )
                        }
                        .onLeft { orderFailure -> exchangeFailureHandler(ctx, orderFailure) }
                } else {
                    val orderFailure =
                        OrderFailure(
                            OrderFailureCode.UNKNOWN_TICKER,
                            orderRequest.ticker.unknownMessage()
                        )
                    exchangeFailureHandler(ctx, orderFailure)
                }
            }
        }

        this.javalinApp.post("/exchange/orders/limit") { ctx ->
            parseCtxBodyMiddleware<LimitOrderRequest>(ctx) { orderRequest ->
                logger.info(objectMapper.writeValueAsString(orderRequest))
                //logger.info("Starting state: {}", marketService.getState().toString())
                // Use optionals to unnest
                if (marketService.validateTicker(orderRequest.ticker)) {
                    val initialQuote = marketService.getStatelessQuoteInLock(orderRequest.ticker)
                    marketService.limitOrderRequest(orderRequest, writeSemaphore)
                        .onRight { response ->
                            ctx.status(HttpStatus.CREATED)
                            ctx.json(response)
                            //logger.info("Final state: {}", marketService.getState().toString())
                            quoteQueue.put(
                                QuoteQueueMessage(
                                    orderRequest,
                                    response,
                                    initialQuote,
                                    marketService.getStatelessQuoteInLock(orderRequest.ticker)
                                )
                            )
                        }
                        .onLeft { orderFailure -> exchangeFailureHandler(ctx, orderFailure) }
                } else {
                    val orderFailure =
                        OrderFailure(OrderFailureCode.UNKNOWN_TICKER, orderRequest.ticker.unknownMessage())
                    exchangeFailureHandler(ctx, orderFailure)
                }
            }
        }

        this.javalinApp.post("/exchange/orders/cancel_all") { ctx ->
            parseCtxBodyMiddleware<ExchangeRequestDto>(ctx) { cancelRequest ->
                logger.info("Starting quote: {}", marketService.getState().toString())
                // Use optionals to unnest
                if (marketService.validateTicker(cancelRequest.ticker)) {
                    val initialQuote = marketService.getStatelessQuoteInLock(cancelRequest.ticker)
                    marketService.allOrderCancel(cancelRequest, writeSemaphore)
                        .onRight { response ->

                            when (response) {
                                is AllOrderCancelResponse.FilledOrdersCancelled ->
                                    ctx.status(HttpStatus.ACCEPTED)

                                else -> ctx.status(HttpStatus.NO_CONTENT)
                            }

                            ctx.json(response)
                            logger.info("Final quote: {}", marketService.getState().toString())
                            quoteQueue.put(
                                QuoteQueueMessage(
                                    cancelRequest,
                                    response,
                                    initialQuote,
                                    marketService.getStatelessQuoteInLock(cancelRequest.ticker)
                                )
                            )
                        }
                        .onLeft { cancelFailure ->
                            ctx.json(mapOf("message" to cancelFailure.second))
                            when (cancelFailure.first) {
                                AllOrderCancelFailureCode.UNKNOWN_TICKER -> ctx.status(HttpStatus.NOT_FOUND)
                                else -> ctx.status(HttpStatus.BAD_REQUEST)
                            }
                        }
                } else {
                    ctx.json(mapOf("message" to cancelRequest.ticker.unknownMessage()))
                    ctx.status(HttpStatus.NOT_FOUND)
                }
            }
        }

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
        heartbeatThread()
        orderQuoteConsumer()
    }

    private fun heartbeatThread() {
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
                val (request, queueableResponse, initialQuote, finalQuote) = quoteQueue.take()
                logger.info("---------OrderQuoteConsumer---------")
                logger.info("Incoming Quote: $initialQuote")

                if (initialQuote != null && finalQuote != null) {
                    if (initialQuote.ticker != finalQuote.ticker) {
                        logger.warn("Illegal ticker combination, must be fixed by better typing: ${initialQuote.ticker}, ${finalQuote.ticker}")
                    } else if (initialQuote.ask != finalQuote.ask || initialQuote.bid != finalQuote.bid) {
                        val sentQuote = finalQuote.getQuote(queueableResponse.exchangeSequenceTimestamp)
                        logger.info("Forcing request: $request")
                        logger.info("Quote transition: [${sentQuote.exchangeSequenceTimestamp}] $initialQuote -> $finalQuote ")
                        val liveSockets = wsUserMap.forEachSubscribingLiveSocket(finalQuote.ticker) { ctx ->
                            ctx.send(QuoteMessage(sentQuote))
                        }
                        logger.info("Updated $liveSockets clients over websockets with new quote");
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
