import arrow.core.Either
import arrow.core.getOrElse
import com.fasterxml.jackson.databind.ObjectMapper
import com.iainschmitt.januaryplaygroundbackend.shared.*
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaProducer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.KafkaSSLConfig
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus
import io.javalin.http.util.NaiveRateLimit
import io.javalin.websocket.WsConfig
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import kotlinx.serialization.*
import kotlinx.serialization.json.*

private data class QuoteQueueMessage<T : Queueable>(
    val request: Any,
    val response: T,
    val initialStatelessQuote: StatelessQuote?,
    val finalStatelessQuote: StatelessQuote?
)

class Backend(db: DatabaseHelper, kafkaConfig: KafkaSSLConfig, secure: Boolean) {
    private val wsUserMap = WsUserMap()
    private val logger by lazy { LoggerFactory.getLogger(Backend::class.java) }
    private val quoteQueue = LinkedBlockingQueue<QuoteQueueMessage<Queueable>>()

    // This is gross and must be narrowed at some point
    private val emittedEventQueue = LinkedBlockingQueue<CreditTransferDto>()
    private val objectMapper = ObjectMapper()
    private val writeSemaphore = Semaphore(1)
    private val readerLightswitch = Lightswitch(writeSemaphore)
    private val producer = AppKafkaProducer(kafkaConfig)

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
    private val exchangeService = ExchangeService(db, secure, wsUserMap, logger)

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
        // # Auth HTTP
        this.javalinApp.get("/health") { ctx -> ctx.result("Up") }
        this.javalinApp.beforeMatched("/auth/") { ctx -> NaiveRateLimit.requestPerTimeUnit(ctx, 1, TimeUnit.SECONDS) }
        this.javalinApp.post("/auth/signup") { authService.signUp(it) }
        this.javalinApp.post("/auth/login") { authService.logIn(it) }
        this.javalinApp.post("/auth/evaluate") { authService.evaluateAuthHandler(it) }
        this.javalinApp.post("/auth/logout") { authService.logOut(it) }
        this.javalinApp.post("/auth/sessions/temporary") { authService.temporarySession(it) }
        this.javalinApp.post("/auth/orchestrator/signup") { ctx -> authService.signUpOrchestrated(ctx, writeSemaphore) }
        this.javalinApp.beforeMatched("/exchange") { exchangeAuthEvaluationMiddleware(it) }

        // ## Querying state
        this.javalinApp.post("/exchange/quote") { getQuote(it) }
        this.javalinApp.post("/exchange/positions") { getUserLongPositions(it) }
        this.javalinApp.post("/exchange/orders") { getUserOrders(it) }
        this.javalinApp.post("/exchange/balance") { getUserBalance(it) }

        // ## Modifying state
        this.javalinApp.post("/exchange/orders/market") { marketOrderRequest(it) }
        this.javalinApp.post("/exchange/orders/limit") { limitOrderRequest(it) }
        this.javalinApp.post("/exchange/orders/cancel-all") { allOrderCancel(it) }
        this.javalinApp.post("/auth/credit-transfer") { ctx ->
            authService.transferCredits(
                ctx,
                writeSemaphore
            ) { dto -> emittedEventQueue.put(dto) }
        }
        this.javalinApp.post("/auth/orchestrator/liquidate") { ctx ->
            authService.liquidateSingleOrchestratedUser(
                ctx,
                writeSemaphore
            )
        }
        this.javalinApp.post("/auth/orchestrator/liquidate-all") { ctx ->
            authService.liquidateAllOrchestratedUsers(
                ctx,
                writeSemaphore
            )
        }

        // # Exchange HTTP

        // ## Modifying non-exchange state
        this.javalinApp.put("/exchange/notification-rule") { createNotificationRule(it) }
        this.javalinApp.delete("/exchange/notification-rule") { deleteNotificationRule(it) }

        // # WebSockets
        this.javalinApp.ws("/ws") { webSocketConsumer(it) }

        this.javalinApp.start(7070)
        heartbeatThread()
        websocketUpdateThread()
        kafkaProducerThread()
    }

    private fun exchangeAuthEvaluationMiddleware(ctx: Context) {
        val maybeAuth = authService.evaluateAuth(ctx)
        if (maybeAuth.isNone()) {
            ctx.json(mapOf("message" to "Auth for user " + "'${maybeAuth.getOrElse { "" }}' is invalid"))
            ctx.status(HttpStatus.UNAUTHORIZED)
            ctx.skipRemainingHandlers()
        }
    }

    private fun getQuote(ctx: Context) {
        //Extra braces below responded in very strange way
        parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
            val ticker = dto.ticker
            if (exchangeService.validateTicker(ticker)) {
                val partialQuote = exchangeService.getStatelessQuoteOutsideLock(ticker, readerLightswitch)
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

    private fun getUserLongPositions(ctx: Context) {
        parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
            val ticker = dto.ticker
            if (exchangeService.validateTicker(ticker)) {
                ctx.status(HttpStatus.OK)
                ctx.json(exchangeService.getUserLongPositions(dto.email, dto.ticker, readerLightswitch))
            } else {
                ctx.status(HttpStatus.NOT_FOUND)
                ctx.json("message" to ticker.unknownMessage())
            }
        }
    }

    private fun getUserOrders(ctx: Context) {
        parseCtxBody<ExchangeRequestDto>(ctx).map { dto ->
            val ticker = dto.ticker
            if (exchangeService.validateTicker(ticker)) {
                ctx.status(HttpStatus.OK)
                ctx.json(exchangeService.getUserOrders(dto.email, dto.ticker, readerLightswitch))
            } else {
                ctx.status(HttpStatus.NOT_FOUND)
                ctx.json("message" to ticker.unknownMessage())
            }
        }
    }

    private fun getUserBalance(ctx: Context) {
        parseCtxBody<BalanceRequestDto>(ctx).map { dto ->
            val result = exchangeService.getUserBalance(dto.userEmail, readerLightswitch)
            if (result != null) {
                ctx.status(HttpStatus.OK)
                ctx.json(BalanceResponse(result))
            } else {
                ctx.status(HttpStatus.NOT_FOUND)
                ctx.json("message" to "Unknown user '${dto.userEmail}'")
            }
        }
    }

    private fun marketOrderRequest(ctx: Context) {
        parseCtxBody<MarketOrderRequest>(ctx).map { orderRequest ->
            logger.info(objectMapper.writeValueAsString(orderRequest))
            //logger.info("Starting state: {}", marketService.getState().toString())
            if (exchangeService.validateTicker(orderRequest.ticker)) {
                val initialStatelessQuote =
                    exchangeService.getStatelessQuoteOutsideLock(orderRequest.ticker, readerLightswitch)
                exchangeService.marketOrderRequest(orderRequest, writeSemaphore)
                    .onRight { response ->
                        ctx.status(HttpStatus.CREATED)
                        ctx.json(response)
                        //logger.info("Final state: {}", marketService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                orderRequest,
                                response,
                                initialStatelessQuote,
                                exchangeService.getStatelessQuoteOutsideLock(orderRequest.ticker, readerLightswitch)
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

    private fun limitOrderRequest(ctx: Context) {
        parseCtxBodyMiddleware<LimitOrderRequest>(ctx) { orderRequest ->
            logger.info(objectMapper.writeValueAsString(orderRequest))
            //logger.info("Starting state: {}", marketService.getState().toString())
            // Use optionals to unnest
            if (exchangeService.validateTicker(orderRequest.ticker)) {
                val initialStatelessQuote =
                    exchangeService.getStatelessQuoteOutsideLock(orderRequest.ticker, readerLightswitch)
                exchangeService.limitOrderRequest(orderRequest, writeSemaphore)
                    .onRight { response ->
                        ctx.status(HttpStatus.CREATED)
                        ctx.json(response)
                        //logger.info("Final state: {}", marketService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                orderRequest,
                                response,
                                initialStatelessQuote,
                                exchangeService.getStatelessQuoteOutsideLock(orderRequest.ticker, readerLightswitch)
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

    private fun allOrderCancel(ctx: Context) {
        parseCtxBodyMiddleware<ExchangeRequestDto>(ctx) { cancelRequest ->
            logger.info("Starting quote: {}", exchangeService.getState().toString())
            // Use optionals to unnest
            if (exchangeService.validateTicker(cancelRequest.ticker)) {
                val initialStatelessQuote =
                    exchangeService.getStatelessQuoteOutsideLock(cancelRequest.ticker, readerLightswitch)
                exchangeService.allOrderCancel(cancelRequest, writeSemaphore)
                    .onRight { response ->
                        when (response) {
                            is AllOrderCancelResponse.FilledOrdersCancelled ->
                                ctx.status(HttpStatus.ACCEPTED)

                            else -> ctx.status(HttpStatus.NO_CONTENT)
                        }
                        ctx.json(response)
                        logger.info("Final quote: {}", exchangeService.getState().toString())
                        quoteQueue.put(
                            QuoteQueueMessage(
                                cancelRequest,
                                response,
                                initialStatelessQuote,
                                exchangeService.getStatelessQuoteOutsideLock(cancelRequest.ticker, readerLightswitch)
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

    private fun createNotificationRule(ctx: Context) {
        parseCtxBodyMiddleware<NotificationRule>(ctx) { notificationRule ->
            exchangeService.createNotificationRule(notificationRule)
            ctx.status(HttpStatus.CREATED)
        }
    }

    private fun deleteNotificationRule(ctx: Context) {
        parseCtxBodyMiddleware<NotificationRule>(ctx) { notificationRule ->
            val deleted = exchangeService.deleteNotificationRule(notificationRule)
            if (deleted) {
                ctx.status(HttpStatus.NO_CONTENT)
            } else {
                ctx.status(HttpStatus.BAD_REQUEST)
            }
        }
    }

    private fun webSocketConsumer(ws: WsConfig) {
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
            } catch (_: IOException) {
                logger.warn("Exception-throwing close")
            }
        }
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

    private fun websocketUpdateThread() {
        Thread {
            while (true) {
                val (request, queueableResponse, initialQuote, finalQuote) = quoteQueue.take()
                orderQuoteConsumer(initialQuote, finalQuote, queueableResponse, request)
                notificationRuleEventProducer()
            }
        }.start()
    }

    private fun kafkaProducerThread() {
        Thread {
            while (true) {
                val notificationRule = emittedEventQueue.take()
                logger.info("--------Kafka Producer-------")

                try {
                    producer.sendSync("orchestrator", "default", Json.encodeToString(notificationRule))
                } catch (e: Exception) {
                    logger.error(e.stackTrace.toString())
                    throw e
                }
            }
        }.start()
    }

    private fun orderQuoteConsumer(
        initialQuote: StatelessQuote?,
        finalQuote: StatelessQuote?,
        queueableResponse: Queueable,
        request: Any
    ) {
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

    private fun notificationRuleEventProducer() {
        logger.info("--------Notification Producer-------")
        val rulesInEffect = exchangeService.getNotificationRules().filter { rule ->
            // This will need to change if other notification rules supported
            if (rule.category === NotificationCategory.CREDIT_BALANCE) {
                val balance = exchangeService.getUserBalance(rule.user, readerLightswitch)
                    ?: return@filter false // Kotlin syntax note
                when (rule.operation) {
                    NotificationOperation.GREATER_THAN -> balance > rule.dimension
                    NotificationOperation.LESS_THAN -> balance < rule.dimension
                }
            } else false
        }.groupBy { rule -> rule.user }

        if (rulesInEffect.isNotEmpty()) {
            val liveSockets = wsUserMap.notifyLiveSocketsInEffect(rulesInEffect)
            logger.info("Updated $liveSockets clients over websockets with notification");
        } else {
            logger.info("No rules in effect")
        }
    }
}
