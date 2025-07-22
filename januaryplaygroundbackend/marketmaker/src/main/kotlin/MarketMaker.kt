import arrow.core.Either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import arrow.core.flatMap
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import kotlin.system.exitProcess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.log

class MarketMaker(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private var trackingQuote: Quote? = null
    private var exchangeSequenceTimestamp: Long = -1
    private val spreadFloor: Int = 3
    private val initialSpread = 5
    private var spread: Int = initialSpread
    private val fallbackQuote = Quote(ticker, 30, 35, System.currentTimeMillis())
    private val marketSize = 3
    private val exchangeRequestDto = ExchangeRequestDto(email, ticker)

    private val orchestratorEmail = "orchestrator@iainschmitt.com"
    private val orchestratorTransferAmount = 150
    private val orchestratorCreditSendRule = NotificationRule(
        orchestratorEmail,
        NotificationCategory.CREDIT_BALANCE,
        NotificationOperation.GREATER_THAN,
        700
    )

    private val backendClient = BackendClient(logger)

    fun main(): Unit = runBlocking {
        Either.catch {
            backendClient.login(email, password)
                .flatMap { backendClient.getStartingState(exchangeRequestDto) }
                .flatMap { mutex.withLock { handleStartingStateInMutex(it) } }
                .onRight { quote ->
                    mutex.withLock {
                        trackingQuote = quote
                    }
                    launch {
                        backendClient.connectWebSocket(
                            email = email,
                            tickers = listOf(ticker),
                            onOpen = { logger.info("WebSocket connection opened") },
                            onQuote = { onQuote(it) },
                            onNotification = { onNotification(it) },
                            onClose = { code, reason -> logger.error("Connection closed: $code, $reason") }
                        )
                    }
                }
        }.mapLeft { throwable -> ClientFailure(-1, throwable.message ?: "Message not provided") }
            .onLeft { error ->
                logger.error("Error: $error")
                val logoutSuccess = backendClient.logout()
                logger.info("Logout successful: $logoutSuccess")
                backendClient.close()
            }
    }

    private suspend fun onQuote(incomingQuote: Quote) {
        mutex.withLock {

            if (ticker != incomingQuote.ticker) {
                return
            }

            logger.info("--------Incoming Quote---------")
            logger.info("Current quote: $trackingQuote")
            logger.info("Incoming quote: $incomingQuote")

            val currentTrackingQuote = trackingQuote
            if (currentTrackingQuote != null) {
                if (incomingQuote.exchangeSequenceTimestamp <= exchangeSequenceTimestamp) {
                    logger.info(
                        "Ignoring stale incoming quote from {}, tracking quote set at {} ",
                        incomingQuote.exchangeSequenceTimestamp.toString(),
                        exchangeSequenceTimestamp.toString()
                    )
                } else if (!currentTrackingQuote.weaklyEquivalent(incomingQuote)) {
                    cancelAndResubmit(
                        incomingQuote,
                        "Market maker could not exit positions after inconsistent quote, exiting",
                        "Market maker could not submit limit orders after quote reset"
                    )
                } else {
                    logger.info(
                        "Incoming quote from {} equivalent to tracking quote {} ",
                        incomingQuote.exchangeSequenceTimestamp.toString(),
                        trackingQuote.toString()
                    )
                }
            } else {
                logger.warn("Illegal market maker state: no tracking quote")
                cancelAndResubmit(
                    incomingQuote,
                    "Market maker could not exit positions on null tracking quote, exiting",
                    "Market maker could not submit limit orders after null tracking quote"
                )
            }
        }
    }

    private suspend fun onNotification(notificationRule: NotificationRule) {
        either {
            if (notificationRule != orchestratorCreditSendRule) raise(ClientFailure(-1, "Invalid notification rule: $notificationRule"))
            backendClient.deleteNotificationRule(notificationRule).bind()
            backendClient.postCreditTransfer(CreditTransferDto(orchestratorEmail, orchestratorTransferAmount)).bind()
            backendClient.putNotificationRule(notificationRule).bind()
            logger.info("Credit exceed notification and transfer to $orchestratorEmail")
        }.mapLeft { error -> logger.error(error.toString()) }
    }

    private suspend fun cancelAndResubmit(incomingQuote: Quote, cancelErrorMsg: String, submitErrorMsg: String) =
        backendClient.retry(exchangeRequestDto, backendClient::postAllOrderCancel, 3)
            .mapLeft {
                logger.error(cancelErrorMsg)
                exitProcess(1)
            }
            .onRight {
                simpleLimitOrderSubmissionInMutex(incomingQuote).mapLeft { logger.warn(submitErrorMsg) }
                    .onRight { pair ->
                        val (ts, quote) = pair
                        exchangeSequenceTimestamp = ts
                        trackingQuote = quote
                    }
            }

    private suspend fun simpleLimitOrderSubmissionInMutex(
        currentQuote: Quote
    ): Either<ClientFailure, Pair<Long, Quote>> {
        return either {
            val nextQuote = calculateNextQuote(currentQuote).bind()
            logger.info("Next quote: {}", nextQuote.toString())

            backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = email,
                    ticker = ticker,
                    size = marketSize,
                    tradeType = TradeType.BUY,
                    price = nextQuote.bid
                )
            ).bind()

            val sellLimitOrder = backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = email,
                    ticker = ticker,
                    size = marketSize,
                    tradeType = TradeType.SELL,
                    price = nextQuote.ask
                )
            ).bind()

            Pair(sellLimitOrder.exchangeSequenceTimestamp, nextQuote)
        }
    }

    private suspend fun initialLimitOrderSubmission(
        currentQuote: Quote
    ): Either<ClientFailure, LimitOrderResponse> {
        return backendClient.postLimitOrderRequest(
            LimitOrderRequest(
                email = email,
                ticker = ticker,
                size = marketSize,
                tradeType = TradeType.BUY,
                price = currentQuote.bid
            )
        ).flatMap { _ ->
            backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = email,
                    ticker = ticker,
                    size = marketSize,
                    tradeType = TradeType.SELL,
                    price = currentQuote.ask
                )
            )
        }
    }

    private fun getMarketMakerImpliedQuote(ticker: Ticker, orders: List<OrderBookEntry>): Quote? {
        // Multiple orders could exist at same price point
        val bids = orders.filter { order -> order.tradeType.isBuy() }
        val asks = orders.filter { order -> order.tradeType.isSell() }
        val bidPrice = bids.maxOfOrNull { order -> order.price }
        val askPrice = asks.minOfOrNull { order -> order.price }

        if (bidPrice != null && askPrice != null) {
            val nonCompliantBids = bids.find { order -> order.price != bidPrice }
            val nonCompliantAsks = asks.find { order -> order.price != askPrice }
            return if (nonCompliantBids != null || nonCompliantAsks != null || bidPrice > askPrice) {
                null
            } else {
                Quote(ticker, bidPrice, askPrice, System.currentTimeMillis())
            }
        } else {
            return null
        }
    }

    private suspend fun handleStartingStateInMutex(state: StartingState): Either<ClientFailure, Quote> {
        val firstQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info("Initial quote: ${firstQuote.ticker}/${firstQuote.bid}/${firstQuote.ask}")
        logger.info("Initial position count: ${positions.sumOf { it.size }}")

        if (positions.isNotEmpty()) {
            if (positions.any { it.positionType != PositionType.LONG }) {
                return ClientFailure(-1, "Unimplemented short positions found").left()
            }
            if (orders.isNotEmpty()) {
                val marketMakerImpliedQuote = getMarketMakerImpliedQuote(ticker, orders)
                return if (marketMakerImpliedQuote == null || marketMakerImpliedQuote != firstQuote) {
                    either {
                        backendClient.postAllOrderCancel(exchangeRequestDto).bind()
                        val secondQuote = calculateNextQuote(firstQuote, true).bind()
                        initialLimitOrderSubmission(secondQuote).bind()
                        backendClient.putNotificationRule(orchestratorCreditSendRule).bind()
                        secondQuote
                    }.mapLeft { failure ->
                        logger.error("Client failure: ${failure.first}/${failure.second}")
                        failure
                    }
                } else {
                    firstQuote.right()
                }
            } else {
                return initialLimitOrderSubmission(firstQuote).map { firstQuote }
            }
        } else {
            logger.warn("No positions, market maker cannot proceed")
            return ClientFailure(-1, "No positions, market maker cannot proceed").left()
        }
    }

    private fun calculateNextQuote(
        incomingQuoteQuote: Quote,
        isFirstQuote: Boolean = false
    ): Either<ClientFailure, Quote> {
        val nextQuoteCandidate = when {
            incomingQuoteQuote.hasBidAskEmpty() && isFirstQuote -> {
                //TODO : this is bad
                logger.warn("Fallback quote used, assumption that market is empty")
                fallbackQuote
            }

            incomingQuoteQuote.hasbidAskFull() -> {
                if ((incomingQuoteQuote.ask - incomingQuoteQuote.bid) == spread) {
                    incomingQuoteQuote
                } else {
                    val midpoint = (incomingQuoteQuote.bid + incomingQuoteQuote.ask) / 2
                    val bid = midpoint - spread / 2
                    val ask = bid + spread
                    Quote(ticker, bid, ask, System.currentTimeMillis())
                }
            }

            incomingQuoteQuote.hasAsksWithoutBids() -> {
                Quote(
                    ticker,
                    incomingQuoteQuote.ask - 1 - spread,
                    incomingQuoteQuote.ask - 1,
                    System.currentTimeMillis()
                )
            }

            incomingQuoteQuote.hasBidsWithoutAsks() -> Quote(
                ticker,
                incomingQuoteQuote.bid + 1,
                incomingQuoteQuote.bid + 1 + spread,
                System.currentTimeMillis()
            )

            else -> {
                //! Mutable state modification!
                spread += 1
                if (trackingQuote == null) {
                    logger.warn("Tracking quote empty: `calculateNextQuote` call will return Left")
                }
                Quote(
                    ticker,
                    trackingQuote?.bid?.minus(1) ?: -1,
                    trackingQuote?.ask?.plus(1) ?: -1,
                    System.currentTimeMillis()
                )
            }
        }

        return nextQuoteCandidate.right().flatMap { quote ->
            if (quote.bid > 0 && quote.ask > 0) {
                quote.right()
            } else {
                ClientFailure(-1, "Illegal `calculateQuote` state").left()
            }
        }
    }
}
