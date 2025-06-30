import arrow.core.*
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import kotlin.random.Random
import kotlin.random.nextInt

class NoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val transactionSize = 1
    private val exchangeRequestDto = ExchangeRequestDto(email, ticker)
    private val mutex = Mutex()

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
                    mutex.withLock {
                        trackingQuote = quote
                    }

                    val wsCoroutineHandle = launch {
                        backendClient.connectWebSocket(
                            email = email,
                            tickers = listOf(ticker),
                            onOpen = { logger.info("WebSocket connection opened") },
                            onQuote = { quote -> onQuote(quote) },
                            onClose = { code, reason -> logger.error("Connection closed: $code, $reason") }
                        )
                    }

                    tradeCoroutine(wsCoroutineHandle)
                }
        }.mapLeft { throwable -> ClientFailure(-1, throwable.message ?: "Message not provided") }
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
                    if (trackingQuote != null &&  trackingQuote!!.hasbidAskFull() &&  Random.nextInt(0..trackingQuote!!.spread()) == 0) {
                        val marketOrder = either<ClientFailure, MarketOrderResponse> {
                            backendClient.postMarketOrderRequest(
                                MarketOrderRequest(
                                    email = email,
                                    ticker = ticker,
                                    size = transactionSize,
                                    tradeType = tradeTypeState,
                                    orderType = OrderType.Market
                                )).bind()
                        }

                        marketOrder.onRight {
                            if (Random.nextInt(0..1) == 0) {
                                tradeTypeState =
                                    if (tradeTypeState.isBuy()) TradeType.SELL else TradeType.BUY
                            }
                        }.onLeft { failure ->
                            logger.warn("Noise trader order failed: $failure")
                            if (failure.first == 400) {
                                tradeTypeState =
                                    if (tradeTypeState.isBuy()) TradeType.SELL else TradeType.BUY

                                if (isFlapping()) {
                                    flapCounter += 1
                                    if (flapCounter < maxFlaps) {
                                        wsCoroutineHandle.cancel(
                                            "Max exchange flap ($maxFlaps) hit",
                                            Throwable("Max exchange flap ($maxFlaps) hit")
                                        )
                                        throw RuntimeException("Max exchange flap ($maxFlaps) hit")
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
            logger.info("--------Incoming Quote---------")
            logger.info("Current quote: $trackingQuote")
            logger.info("Incoming quote: $incomingQuote")
            trackingQuote = incomingQuote
        }
    }

    private suspend fun isFlapping(): Boolean {

        val balanceFlap = backendClient.getUserBalance(email)
            // failures interpreted as flapping
            .flatMap { balanceResponse ->
                // Conservative static analysis
                if ((trackingQuote != null) && (trackingQuote!!.ask > balanceResponse.balance)) {
                    ClientFailure(1, "Balance flapping").left()
                } else balanceResponse.right()
            }
        val positionsFlap = backendClient.getUserLongPositions(ExchangeRequestDto(email, ticker))
            .flatMap { positionsResponse ->
                if (positionsResponse.isEmpty()) {
                    ClientFailure(1, "Positions flapping").left()
                } else positionsResponse.right()
            }

        return balanceFlap.isLeft() && positionsFlap.isRight()
    }

    private suspend fun handleStartingState(state: StartingState): Either<ClientFailure, Quote> {
        val startingQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info("Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}")
        logger.info("Initial position count: ${positions.count()}")

        if (positions.any { it.positionType != PositionType.LONG }) {
            return ClientFailure(-1, "Unimplemented short positions found").left()
        } else {
            tradeTypeState = if (positions.any { it.positionType == PositionType.LONG }) {
                TradeType.SELL
            } else {
                TradeType.BUY
            }
        }

        return when (orders.size) {
            0 -> startingQuote.right()
            else -> backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                logger.error("Client failure: ${failure.first}/${failure.second}")
                failure
            }.map { startingQuote }
        }
    }
}
