import arrow.core.Either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import org.slf4j.LoggerFactory
import arrow.core.flatMap
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.Logger
import kotlin.system.exitProcess


private data class StartingState(
    val quote: Quote,
    val positions: List<PositionRecord>,
    val orders: List<OrderBookEntry>
)

class MarketMaker(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
    private var trackingQuote: Quote? = null,
) {
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
                    trackingQuote = quote
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
        if (trackingQuote != null) {
            if (trackingQuote != incomingQuote) {
                backendClient.retry(exchangeRequestDto, backendClient::postAllOrderCancel, 3)
                    .onLeft {
                        logger.error("Market maker could not exit positions on null tracking quote, exiting")
                        exitProcess(1)
                    }.onRight {
                        this.trackingQuote = incomingQuote
                        simpleLimitOrderSubmission(incomingQuote).onLeft { logger.warn("Market maker could not submit limit orders after quote reset") }
                    }
            }
        } else {
            logger.warn("Illegal market maker state: no tracking quote")
            backendClient.retry(exchangeRequestDto, backendClient::postAllOrderCancel, 3)
                .onLeft {
                    logger.error("Market maker could not exit positions on null tracking quote, exiting")
                    exitProcess(1)
                }.onRight {
                    this.trackingQuote = incomingQuote
                    simpleLimitOrderSubmission(incomingQuote).onLeft { logger.warn("Market maker could not submit limit orders after null tracking quote") }
                }
        }
    }

    private suspend fun simpleLimitOrderSubmission(
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
        val bidPrice = orders.filter { order -> order.tradeType.isBuy() }.minOfOrNull { order -> order.price }
        val askPrice = orders.filter { order -> order.tradeType.isSell() }.maxOfOrNull { order -> order.price }

        if (bidPrice != null && askPrice != null) {
            val nonCompliantBids = orders.find { order -> order.tradeType.isBuy() && order.tradeType.isBuy() }
            val nonCompliantAsks = orders.find { order -> order.tradeType.isSell() && order.tradeType.isSell() }
            return if (nonCompliantBids != null || nonCompliantAsks != null) {
                null
            } else {
                Quote(ticker, bidPrice, askPrice)
            }
        } else {
            return null
        }
    }

    private suspend fun handleStartingState(state: StartingState): Either<ClientFailure, Quote> {
        val startingQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info("Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}")
        logger.info("Initial position count: ${positions.count()}")

        if (positions.isNotEmpty()) {
            if (positions.any { it.positionType != PositionType.LONG }) {
                return Either.Left(ClientFailure(-1, "Unimplemented short positions found"))
            }
            if (orders.isNotEmpty()) {
                val orderImpliedQuote = getMarketMakerImpliedQuote(ticker, orders)
                return if (orderImpliedQuote == null || orderImpliedQuote != startingQuote) {
                    backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                        logger.error("Client failure: ${failure.first}/${failure.second}")
                        failure
                    }.map {
                        simpleLimitOrderSubmission(startingQuote)
                    }.map { startingQuote }
                } else {
                    Either.Right(startingQuote)
                }
            } else {
                return simpleLimitOrderSubmission(startingQuote).map { startingQuote }
            }
        } else {
            logger.warn("No positions, market maker cannot proceed")
            return Either.Left(ClientFailure(-1, "No positions, market maker cannot proceed"))
        }
    }
}
