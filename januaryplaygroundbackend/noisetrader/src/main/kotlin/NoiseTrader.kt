import arrow.core.*
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlin.random.Random
import kotlin.random.nextInt
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import kotlin.math.min

fun Logger.withPrefix(prefix: String) = object : Logger by this {
    override fun error(message: String?) = this@withPrefix.error("$prefix: $message")
    override fun warn(message: String?) = this@withPrefix.warn("$prefix: $message")
    override fun info(message: String?) = this@withPrefix.info("$prefix: $message")
    override fun debug(message: String?) = this@withPrefix.debug("$prefix: $message")
    // Implement other methods as needed
}

class NoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val defaultLogger: Logger,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val transactionSize = 1
    private val exchangeRequestDto = ExchangeRequestDto(email, ticker)
    private val mutex = Mutex()
    
    private val logger = defaultLogger.withPrefix("[NT $email]")
    private val backendClient = BackendClient(logger)

    private var trackingQuote: Quote? = null
    private var flapCounter = 0
    private val maxFlaps = 5

    fun main(): Unit = runBlocking {
        Either.catch {
            backendClient.login(email, password)
            either {
                val startingState = backendClient.getStartingState(exchangeRequestDto).bind()
                handleStartingState(startingState).bind()
            }
                    .onRight { quote ->
                        mutex.withLock { trackingQuote = quote }

                        val wsCoroutineHandle = launch {
                            backendClient.connectWebSocket(
                                    email = email,
                                    tickers = listOf(ticker),
                                    onOpen = { logger.info("WebSocket connection opened") },
                                    onQuote = { quote -> onQuote(quote) },
                                    onClose = { code, reason ->
                                        logger.error("WebSocket connection closed: $code, $reason")
                                    }
                            )
                        }

                        tradeCoroutine(wsCoroutineHandle)
                    }
        }
                .mapLeft { throwable ->
                    ClientFailure(-1, throwable.message ?: "Message not provided")
                }
                .onLeft { error ->
                    logger.error("Error: $error")
                    val logoutSuccess = backendClient.logout()
                    logger.info("Logout successful: $logoutSuccess")
                    backendClient.close()
                }
    }

    private fun CoroutineScope.tradeCoroutine(wsCoroutineHandle: Job) {
        launch {
            while (true) {
                delay(1.seconds)
                mutex.withLock {
                    // Conservative static anaylsis
                    if (trackingQuote != null &&
                                    trackingQuote!!.hasbidAskFull() &&
                                    Random.nextInt(0.. min(trackingQuote!!.spread(), 5 ))  == 0
                    ) {
                        val marketOrder =
                                either<ClientFailure, MarketOrderResponse> {
                                    backendClient
                                            .postMarketOrderRequest(
                                                    MarketOrderRequest(
                                                            email = email,
                                                            ticker = ticker,
                                                            size = transactionSize,
                                                            tradeType = tradeTypeState,
                                                            orderType = OrderType.Market
                                                    )
                                            )
                                            .bind()
                                }

                        marketOrder
                                .onRight {
                                    if (Random.nextInt(0..1) == 0) {
                                        tradeTypeState =
                                                if (tradeTypeState.isBuy()) TradeType.SELL
                                                else TradeType.BUY
                                    }
                                }
                                .onLeft { failure ->
                                    logger.warn("Noise trader order failed: $failure")
                                    if (failure.first == 400) {
                                        val oldTradeTypeState = tradeTypeState
                                        tradeTypeState =
                                                if (tradeTypeState.isBuy()) TradeType.SELL
                                                else TradeType.BUY
                                        logger.info("Trade type: $oldTradeTypeState -> $tradeTypeState")
                                        if (isFlapping()) {
                                            flapCounter += 1
                                            if (flapCounter < maxFlaps) {

                                                backendClient.getUserLongPositions(ExchangeRequestDto(email, ticker)).onRight {
                                                    if (it.isNotEmpty()) {
                                                        logger.info("Selling ${it.size} positions prior to exiting")
                                                        backendClient.postMarketOrderRequest(
                                                            MarketOrderRequest(
                                                                email = email,
                                                                ticker = ticker,
                                                                size = it.size,
                                                                tradeType = TradeType.SELL,
                                                                orderType = OrderType.Market
                                                            )
                                                        )
                                                    }
                                                }

                                                wsCoroutineHandle.cancel(
                                                        "Max exchange flap ($maxFlaps) hit",
                                                        Throwable(
                                                                "Max exchange flap ($maxFlaps) hit"
                                                        )
                                                )
                                                throw RuntimeException(
                                                        "Max exchange flap ($maxFlaps) hit"
                                                )
                                            }
                                        } else flapCounter = 0
                                    }
                                }
                    }
                }
            }
        }
    }

    private suspend fun onQuote(incomingQuote: Quote) {
        mutex.withLock {
            logger.debug("--------Incoming Quote---------")
            logger.debug("Current quote: {}", trackingQuote)
            logger.debug("Incoming quote: {}", incomingQuote)
            trackingQuote = incomingQuote
        }
    }

    private suspend fun isFlapping(): Boolean {

        val balanceFlap =
                backendClient.getUserBalance(email)
                        // failures interpreted as flapping
                        .flatMap { balanceResponse ->
                            // Conservative static analysis
                            if ((trackingQuote != null) &&
                                            ((trackingQuote!!.ask * transactionSize) > balanceResponse.balance)
                            ) {
                                ClientFailure(1, "Balance flapping").left()
                            } else balanceResponse.right()
                        }
        val positionsFlap =
                backendClient.getUserLongPositions(ExchangeRequestDto(email, ticker)).flatMap {
                        positionsResponse ->
                    if (positionsResponse.isEmpty()) {
                        ClientFailure(1, "Positions flapping").left()
                    } else positionsResponse.right()
                }
        // logger.info("Flap evals: ${balanceFlap.isLeft()} balance/${balanceFlap.isRight()}
        // positions")
        return balanceFlap.isLeft() && positionsFlap.isLeft()
    }

    private suspend fun handleStartingState(state: StartingState): Either<ClientFailure, Quote> {
        val startingQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info(
                "Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}"
        )
        logger.info("Initial position count: ${positions.count()}")

        if (positions.any { it.positionType != PositionType.LONG }) {
            return ClientFailure(-1, "Unimplemented short positions found").left()
        } else {
            tradeTypeState =
                    if (positions.any { it.positionType == PositionType.LONG }) {
                        TradeType.SELL
                    } else {
                        TradeType.BUY
                    }
        }

        return when (orders.size) {
            0 -> startingQuote.right()
            else ->
                    backendClient
                            .postAllOrderCancel(exchangeRequestDto)
                            .mapLeft { failure ->
                                logger.error("Client failure: ${failure.first}/${failure.second}")
                                failure
                            }
                            .map { startingQuote }
        }
    }
}
