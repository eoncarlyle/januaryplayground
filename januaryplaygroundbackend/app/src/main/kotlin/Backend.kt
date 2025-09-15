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
import io.javalin.websocket.WsContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import kotlinx.serialization.*
import kotlinx.serialization.json.*

private data class OrderQueueMessage(
    val request: Any, // This is only ever an OrderRequest or an ExchangeRequestDto,
    // but not having union types makes this annoying; realistically should make this a different queue?
    val response: Queueable,
    val initialStatelessQuote: StatelessQuote?,
    val finalStatelessQuote: StatelessQuote?
)

class Backend(db: DatabaseHelper, kafkaConfig: KafkaSSLConfig, secure: Boolean) {
    private val authenticatedWsUserMap = WsUserMap()
    private val publicWsUsers = HashSet<WsContext>()
    private val logger by lazy { LoggerFactory.getLogger(Backend::class.java) }
    private val orderQueue = LinkedBlockingQueue<OrderQueueMessage>()

    private val creditTransferQueue = LinkedBlockingQueue<CreditTransferDto>()
    private val objectMapper = ObjectMapper()
    private val writeSemaphore = Semaphore(1)
    private val readerLightswitch = Lightswitch(writeSemaphore)
    private val producer = AppKafkaProducer(kafkaConfig)

    private val javalinApp = Javalin.create { config ->
        config.bundledPlugins.enableCors { cors ->
            // TODO: specify this correctly in production
            cors.addRule {
                it.allowHost("http://localhost:5173", "https://demo.iainschmitt.com")
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

    private val authService = AuthService(db, secure, authenticatedWsUserMap, logger)
    private val exchangeService = ExchangeService(db, secure, authenticatedWsUserMap, logger)

    private fun exchangeFailureHandler(ctx: Context, orderFailure: OrderFailure) {
        ctx.json(mapOf("message" to orderFailure.second))
        logger.error("${ctx.body()} failure: ${orderFailure.second}")
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
            ) { dto -> creditTransferQueue.put(dto) }
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
        this.javalinApp.ws("/ws/authenticated") { privateWebSocketConsumer(it) }
        this.javalinApp.ws("/ws/public") { publicWebSocketConsumer(it) }

        this.javalinApp.start(7070)
        heartbeatThread()
        orderQueueConsumerThread()
        kafkaProducerThread()
        publicWebsocketHeartbeat()
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
                        orderQueue.put(
                            OrderQueueMessage(
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

                        orderQueue.put(
                            OrderQueueMessage(
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
                            is AllOrderCancelResponse.SomeOrdersCancelled ->
                                ctx.status(HttpStatus.ACCEPTED)

                            else -> ctx.status(HttpStatus.OK)
                        }
                        ctx.json(response)
                        logger.info("Final quote: {}", exchangeService.getState().toString())
                        orderQueue.put(
                            OrderQueueMessage(
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

    private fun privateWebSocketConsumer(ws: WsConfig) {
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

    private fun publicWebSocketConsumer(ws: WsConfig) {
        ws.onConnect { publicWsUsers.add(it) }
        ws.onClose { publicWsUsers.remove(it) }
    }

    private fun heartbeatThread() {
        Thread {
            while (true) {
                Thread.sleep(1500)
                authenticatedWsUserMap.forEachLiveSocket { ctx ->
                    ctx.send(objectMapper.writeValueAsString(ServerTimeMessage(System.currentTimeMillis())))
                }
            }
        }.start()
    }

    private fun publicWebsocketHeartbeat() {
        Thread {
            while (true) {
                Thread.sleep(500)
                publicWsUsers.forEachLiveSocket { ctx ->
                    ctx.send(AllQuotesMessage(exchangeService.getAllStatelessQuotesInLock(readerLightswitch)))
                }
            }
        }.start()
    }

    private fun orderQueueConsumerThread() {
        Thread {
            while (true) {
                val (request, queueableOrderResponse, initialQuote, finalQuote) = orderQueue.take()
                orderResponseConsumer(initialQuote, finalQuote, queueableOrderResponse, request)
                notificationRuleEventProducer()
            }
        }.start()
    }

    private fun kafkaProducerThread() {
        Thread {
            while (true) {
                val notificationRule = creditTransferQueue.take()
                logger.info("--------Kafka Producer-------")

                try {
                    val event = Json.encodeToString(notificationRule)
                    logger.info(event)
                    producer.sendSync("orchestrator", "default", event)

                    val orderLivePublicSockets = publicWsUsers.forEachLiveSocket {
                        it.send(notificationRule)
                    }
                    logger.info("Updated $orderLivePublicSockets clients over public websockets with incoming credit transfer notification")
                } catch (e: Exception) {
                    logger.error(e.stackTrace.toString())
                    throw e
                }
            }
        }.start()
    }

    private fun orderResponseConsumer(
        initialQuote: StatelessQuote?,
        finalQuote: StatelessQuote?,
        queueableOrderResponse: Queueable,
        request: Any
    ) {
        logger.info("---------OrderResponseConsumer---------")
        logger.info("Incoming Quote: $initialQuote")

        val orderLivePublicSockets = publicWsUsers.forEachLiveSocket {
            it.send(queueableOrderResponse)
        }
        logger.info("Updated $orderLivePublicSockets clients over public websockets with new quote")

        if (initialQuote != null && finalQuote != null) {
            if (initialQuote.ticker != finalQuote.ticker) {
                logger.warn("Illegal ticker combination, must be fixed by better typing: ${initialQuote.ticker}, ${finalQuote.ticker}")
            } else if (initialQuote.ask != finalQuote.ask || initialQuote.bid != finalQuote.bid) {
                val sentQuote = finalQuote.getQuote(queueableOrderResponse.exchangeSequenceTimestamp)
                logger.info("Forcing request: $request")
                logger.info("Quote transition: [${sentQuote.exchangeSequenceTimestamp}] $initialQuote -> $finalQuote ")
                val liveAuthenticatedSockets = authenticatedWsUserMap.forEachSubscribingLiveSocket(finalQuote.ticker) {
                    it.send(QuoteMessage(sentQuote))
                }
                logger.info("Updated $liveAuthenticatedSockets clients over authenticated websockets with new quote")

                val quoteLivePublicSockets = publicWsUsers.forEachLiveSocket {
                    it.send(QuoteMessage(sentQuote))
                }

                logger.info("Updated $quoteLivePublicSockets clients over public websockets with new quote")
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
        val state = exchangeService.getState()
        logger.info("Current state: ${state.first} positions, ${state.second} credits")
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
        }.groupBy { it.user }

        if (rulesInEffect.isNotEmpty()) {
            val liveAuthenticatedSockets = authenticatedWsUserMap.notifyLiveSocketsInEffect(rulesInEffect)
            logger.info("Updated $liveAuthenticatedSockets clients over authenticated websockets with notification")
        } else {
            logger.info("No rules in effect")
        }
    }
}
