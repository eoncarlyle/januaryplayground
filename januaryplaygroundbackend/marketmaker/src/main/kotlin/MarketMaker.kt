import arrow.core.Either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import kotlin.system.exitProcess
import arrow.atomic.AtomicInt
import arrow.atomic.AtomicBoolean
import arrow.atomic.Atomic

private enum class SpreadStateChange {
    INCREMENT, DECREMENT, STABLE
}

private data class InterQuoteChange(
    val bid: Int,
    val ask: Int,
    val spreadStateChange: SpreadStateChange
)

class MarketMaker(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
) {
    private val trackingQuote: Atomic<Quote?> = Atomic(null)
    private val spreadState: AtomicInt = AtomicInt(0)
    private val openForQuote: AtomicBoolean = AtomicBoolean(true)

    private val marketSize = 3
    private val exchangeRequestDto = ExchangeRequestDto(email, ticker)

    private val backendClient = BackendClient(logger)
    //TODO: market initialisaiton needs to be tied to this

    fun main(): Unit = runBlocking {
        Either.catch {
            backendClient.login(email, password)
                .flatMap { _ -> backendClient.getStartingState(exchangeRequestDto) }
                .flatMap { state -> handleStartingState(state) }
                .onRight { quote ->
                    trackingQuote.set(quote.getQuote())
                    launch {
                        backendClient.connectWebSocket(
                            email = email,
                            onOpen = { logger.info("WebSocket connection opened") },
                            onQuote = { quote -> onQuote(quote) },
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
        logger.info("Incoming Quote")
        logger.info("Current quote: {}", trackingQuote.get().toString())
        logger.info("Incoming quote: {}", incomingQuote.toString())

        if (!openForQuote.get()) {
            logger.info("Discarding quote, `openForQuote` false")
            return
        }

        val currentTrackingQuote = trackingQuote.get()
        if (currentTrackingQuote != null) {
            if (incomingQuote.tick < currentTrackingQuote.tick) {
                logger.info(
                    "Ignoring stale incoming quote from {}, tracking quote set at {} ",
                    incomingQuote.tick.toString(),
                    trackingQuote.toString()
                )
            } else if (currentTrackingQuote != incomingQuote) {
                openForQuote.set(false)
                backendClient.retry(exchangeRequestDto, backendClient::postAllOrderCancel, 3)
                    .mapLeft {
                        logger.error("Market maker could not exit positions after inconsistent quote, exiting")
                        exitProcess(1)
                    }
                    .map {
                        simpleLimitOrderSubmission(currentTrackingQuote, incomingQuote)
                            .mapLeft {
                                logger.warn("Market maker could not submit limit orders after quote reset")
                            }
                            .map { quote -> trackingQuote.set(quote) }
                    }
                openForQuote.set(true)
            }
        } else {
            logger.warn("Illegal market maker state: no tracking quote")
            openForQuote.set(false)
            backendClient.retry(exchangeRequestDto, backendClient::postAllOrderCancel, 3)
                .mapLeft {
                    logger.error("Market maker could not exit positions on null tracking quote, exiting")
                    exitProcess(1)
                }.map {
                    simpleLimitOrderSubmission(
                        currentTrackingQuote,
                        incomingQuote
                    ).mapLeft {
                        logger.warn("Market maker could not submit limit orders after null tracking quote")
                    }
                        .map { quote ->
                            trackingQuote.set(quote)
                        }
                }
            openForQuote.set(true)
        }
    }

    private suspend fun simpleLimitOrderSubmission(
        priorQuote: Quote?,
        currentQuote: Quote
    ): Either<ClientFailure, Quote> {
        if (priorQuote == null && (currentQuote.bid == -1 || currentQuote.ask == -1)) {
            return ClientFailure(-1, "Not enough tracking state calculate new quote, closing").left()
        }

        val interQuoteChange = getInterQuoteChange(priorQuote, currentQuote)
        if (interQuoteChange.spreadStateChange == SpreadStateChange.INCREMENT) {
            spreadState.incrementAndGet()
        } else if (interQuoteChange.spreadStateChange == SpreadStateChange.DECREMENT) {
            spreadState.decrementAndGet()
        }

        logger.info(interQuoteChange.toString())
        return backendClient.postLimitOrderRequest(
            LimitOrderRequest(
                email = email,
                ticker = ticker,
                size = marketSize,
                tradeType = TradeType.BUY,
                price = interQuoteChange.bid
            )
        ).flatMap { _ ->
            backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = email,
                    ticker = ticker,
                    size = marketSize,
                    tradeType = TradeType.SELL,
                    price = interQuoteChange.ask
                )
            )
        }.flatMap { _ ->
            Quote(ticker, interQuoteChange.bid, interQuoteChange.ask, System.currentTimeMillis()).right()
        }
    }

    private fun getInterQuoteChange(
        priorQuote: Quote?,
        currentQuote: Quote
    ): InterQuoteChange {
        return if (priorQuote == null || (currentQuote.hasbidAskFull())) {
            InterQuoteChange(currentQuote.bid, currentQuote.ask, SpreadStateChange.STABLE)
        } else {

            when {
                currentQuote.hasAsksWithoutBids() -> {
                    if (spreadState.get() <= 0) {
                        InterQuoteChange(priorQuote.bid - 1, priorQuote.ask - 1, SpreadStateChange.STABLE)
                    } else {
                        InterQuoteChange(priorQuote.bid - 1, priorQuote.ask, SpreadStateChange.DECREMENT)
                    }
                }
                currentQuote.hasBidsWithoutAsks() -> {
                    if (spreadState.get() <= 0) {
                        InterQuoteChange(priorQuote.bid + 1, priorQuote.ask + 1, SpreadStateChange.STABLE)
                    } else {
                        InterQuoteChange(priorQuote.bid, priorQuote.ask + 1, SpreadStateChange.DECREMENT)
                    }
                }
                else -> InterQuoteChange(priorQuote.bid - 1, priorQuote.ask + 1, SpreadStateChange.INCREMENT)
            }
        }
    }

    private suspend fun initialLimitOrderSubmission(
        currentQuote: SafeQuote
    ): Either<ClientFailure, LimitOrderResponse> {
        return backendClient.postLimitOrderRequest(
            LimitOrderRequest(
                email = email,
                ticker = ticker,
                size = marketSize,
                tradeType = TradeType.BUY,
                price = currentQuote.bid.value
            )
        ).flatMap { _ ->
            backendClient.postLimitOrderRequest(
                LimitOrderRequest(
                    email = email,
                    ticker = ticker,
                    size = marketSize,
                    tradeType = TradeType.SELL,
                    price = currentQuote.ask.value
                )
            )
        }
    }

    private fun getMarketMakerImpliedQuote(ticker: Ticker, orders: List<OrderBookEntry>): Quote? {
        // Multiple orders could exist at same price point
        val bidPrice = orders.filter { order -> order.tradeType.isBuy() }.minOfOrNull { order -> order.price }
        val askPrice = orders.filter { order -> order.tradeType.isSell() }.maxOfOrNull { order -> order.price }

        if (bidPrice != null && askPrice != null) {
            val nonCompliantBids = orders.find { order -> order.tradeType.isBuy() && order.tradeType.isBuy() }
            val nonCompliantAsks = orders.find { order -> order.tradeType.isSell() && order.tradeType.isSell() }
            return if (nonCompliantBids != null || nonCompliantAsks != null) {
                null
            } else {
                Quote(ticker, bidPrice, askPrice, System.currentTimeMillis())
            }
        } else {
            return null
        }
    }

    private suspend fun handleStartingState(state: StartingState): Either<ClientFailure, SafeQuote> {
        val startingQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info("Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}")
        logger.info("Initial position count: ${positions.sumOf { it.size } }")

        if (positions.isNotEmpty()) {
            if (positions.any { it.positionType != PositionType.LONG }) {
                return ClientFailure(-1, "Unimplemented short positions found").left()
            }
            if (orders.isNotEmpty()) {
                val orderImpliedQuote = getMarketMakerImpliedQuote(ticker, orders)
                return if (orderImpliedQuote == null || !quotesEqual(orderImpliedQuote, startingQuote)) {
                    backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                        logger.error("Client failure: ${failure.first}/${failure.second}")
                        failure
                    }.map {
                        initialLimitOrderSubmission(startingQuote)
                    }.map { startingQuote }
                } else {
                    startingQuote.right()
                }
            } else {
                return initialLimitOrderSubmission(startingQuote).map { startingQuote }
            }
        } else {
            logger.warn("No positions, market maker cannot proceed")
            return Either.Left(ClientFailure(-1, "No positions, market maker cannot proceed"))
        }
    }

    private fun quotesEqual(quote: Quote, safeQuote: SafeQuote): Boolean {
        return (quote.ask == safeQuote.ask.value) && (quote.ask == safeQuote.ask.value)
    }
}
