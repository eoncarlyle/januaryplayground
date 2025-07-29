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
                        mutex.withLock { trackingQuote = quote }

                        val wsCoroutineHandle = launch {
                            backendClient.connectWebSocket(
                                    email = email,
                                    tickers = listOf(ticker),
                                    onOpen = { logger.info("NT $email WebSocket connection opened") },
                                    onQuote = { quote -> onQuote(quote) },
                                    onClose = { code, reason ->
                                        logger.error("NT $email connection closed: $code, $reason")
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
                    logger.error("NT $email error: $error")
                    val logoutSuccess = backendClient.logout()
                    logger.info("NT $email Logout successful: $logoutSuccess")
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
                                    Random.nextInt(0..trackingQuote!!.spread()) == 0
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
                                    logger.warn("NT $email noise trader order failed: $failure")
                                    if (failure.first == 400) {
                                        tradeTypeState =
                                                if (tradeTypeState.isBuy()) TradeType.SELL
                                                else TradeType.BUY

                                        if (isFlapping()) {
                                            flapCounter += 1
                                            if (flapCounter < maxFlaps) {
                                                wsCoroutineHandle.cancel(
                                                        "Max exchange flap ($maxFlaps) hit",
                                                        Throwable(
                                                                "Max exchange flap ($maxFlaps) hit"
                                                        )
                                                )
                                                throw RuntimeException(
                                                        "NT $email Max exchange flap ($maxFlaps) hit"
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
                "NT $email Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}"
        )
        logger.info("NT $email initial position count: ${positions.count()}")

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
                                logger.error("NT $email Client failure: ${failure.first}/${failure.second}")
                                failure
                            }
                            .map { startingQuote }
        }
    }
}
