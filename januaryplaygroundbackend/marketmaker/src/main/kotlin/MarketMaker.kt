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
import kotlin.math.exp
import kotlin.random.Random

class MarketMaker(
    private val marketMakerEmail: String,
    private val password: String,
    private val tickers: List<Ticker>,
    private val logger: Logger,
) {
    private val mutex = Mutex()
    private var trackingQuote: Quote? = null
    private var exchangeSequenceTimestamp: Long = -1
    private val spreadFloor: Int = 2
    private val initialSpread = 5
    private var spread: Int = initialSpread
    private val marketSize = 3
    private val exchangeRequestDto = ExchangeRequestDto(marketMakerEmail, tickers)

    private val orchestratorEmail = "orchestrator@iainschmitt.com"
    private val orchestratorTransferAmount = 150
    private var trackingNotificationRule: NotificationRule? = null
    private val notificationCreditAmount = 650

    private val backendClient = BackendClient(logger)

    fun main(): Unit = runBlocking {
        Either.catch {
            backendClient.login(marketMakerEmail, password)
                .flatMap { backendClient.getStartingState(exchangeRequestDto) }
                .flatMap { mutex.withLock { handleStartingStateInMutex(it) } }
                .onRight { quote ->
                    mutex.withLock {
                        trackingQuote = quote
                    }
                    launch {
                        backendClient.connectWebSocket(
                            email = marketMakerEmail,
                            tickers = tickers,
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

            if (!tickers.contains(incomingQuote.ticker)) {
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


    private suspend fun onNotification(incomingNotificationRule: NotificationRule) {
        logger.info("Notification sent to market maker")
        mutex.withLock {
            either {
                if (incomingNotificationRule != trackingNotificationRule) raise(
                    ClientFailure(
                        -1,
                        "Invalid notification rule: $incomingNotificationRule"
                    )
                )

                backendClient.deleteNotificationRule(incomingNotificationRule).bind()
                backendClient.postCreditTransfer(
                    CreditTransferDto(
                        marketMakerEmail,
                        orchestratorEmail,
                        orchestratorTransferAmount
                    )
                ).bind()

                val nextTrackingNotificationRule = NotificationRule(
                    marketMakerEmail,
                    NotificationCategory.CREDIT_BALANCE,
                    NotificationOperation.GREATER_THAN,
                    System.currentTimeMillis(),
                    notificationCreditAmount
                )

                backendClient.putNotificationRule(nextTrackingNotificationRule).bind()
                trackingNotificationRule = nextTrackingNotificationRule
                logger.info("New tracking notification rule $nextTrackingNotificationRule")

                // Kotlin talk: string interpolation
                logger.info("Credit exceed notification and transfer to $orchestratorEmail with notification reset")

                if (spread > 0 && exp((-1/spread).toDouble()) > Random.nextFloat() && spread > spreadFloor) {
                    logger.info("Spread narrowing $spread -> ${spread - 1}")
                    spread -= 1
                }

            }.mapLeft { error -> logger.error(error.toString()) }
        }
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
                    email = marketMakerEmail,
                    ticker = tickers,
                    size = marketSize,
                    tradeType = TradeType.BUY,
                    price = nextQuote.bid
                )
            ).bind()

            val sellLimitOrder = backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = marketMakerEmail,
                    ticker = tickers,
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
                email = marketMakerEmail,
                ticker = tickers,
                size = marketSize,
                tradeType = TradeType.BUY,
                price = currentQuote.bid
            )
        ).flatMap { _ ->
            backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = marketMakerEmail,
                    ticker = tickers,
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
                val marketMakerImpliedQuote = getMarketMakerImpliedQuote(tickers, orders)
                return if (marketMakerImpliedQuote == null || marketMakerImpliedQuote != firstQuote) {
                    either {
                        backendClient.postAllOrderCancel(exchangeRequestDto).bind()
                        val secondQuote = calculateNextQuote(firstQuote, true).bind()
                        initialLimitOrderSubmission(secondQuote).bind()
                        trackingNotificationRule = NotificationRule(
                            marketMakerEmail,
                            NotificationCategory.CREDIT_BALANCE,
                            NotificationOperation.GREATER_THAN,
                            System.currentTimeMillis(),
                            notificationCreditAmount
                        )

                        logger.info("Starting tracking notification rule $trackingNotificationRule")
                        // Kotlin talk: conservative static analysis
                        backendClient.putNotificationRule(trackingNotificationRule!!).bind()
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
                getFallbackQuote(ticker)
            }

            incomingQuoteQuote.hasbidAskFull() -> {
                if ((incomingQuoteQuote.ask - incomingQuoteQuote.bid) == spread) {
                    incomingQuoteQuote
                } else {
                    val midpoint = (incomingQuoteQuote.bid + incomingQuoteQuote.ask) / 2
                    val bid = midpoint - spread / 2
                    val ask = bid + spread
                    Quote(tickers, bid, ask, System.currentTimeMillis())
                }
            }

            incomingQuoteQuote.hasAsksWithoutBids() -> {
                Quote(
                    tickers,
                    incomingQuoteQuote.ask - 1 - spread,
                    incomingQuoteQuote.ask - 1,
                    System.currentTimeMillis()
                )
            }

            incomingQuoteQuote.hasBidsWithoutAsks() -> Quote(
                tickers,
                incomingQuoteQuote.bid + 1,
                incomingQuoteQuote.bid + 1 + spread,
                System.currentTimeMillis()
            )

            else -> {
                //! Mutable state modification!
                val previousSpread = spread;
                spread += 1
                logger.info("Spread widening $previousSpread -> $spread")
                if (trackingQuote == null) {
                    logger.warn("Tracking quote empty: `calculateNextQuote` call will return Left")
                }
                Quote(
                    tickers,
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


    private fun getFallbackQuote(ticker: Ticker) = Quote(ticker, 30, 35, System.currentTimeMillis())

}
