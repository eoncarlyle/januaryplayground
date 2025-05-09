import arrow.core.Either
import arrow.core.flatMap
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import com.iainschmitt.januaryplaygroundbackend.shared.*

private data class StartingState(
    val quote: Quote,
    val positions: List<PositionRecord>,
    val orders: List<OrderBookEntry>
)


private suspend fun onStartup(
    backendClient: BackendClient,
    exchangeRequestDto: ExchangeRequestDto
): Either<ClientFailure, StartingState> {
    val maybeQuote = backendClient.getQuote(exchangeRequestDto)
    val maybePositions = backendClient.getUserLongPositions(exchangeRequestDto)
    val maybeOrders = backendClient.getUserOrders(exchangeRequestDto)

    return maybeQuote.flatMap { quote ->
        maybePositions.flatMap { position ->
            maybeOrders.map { orders ->
                StartingState(
                    quote,
                    position,
                    orders
                )
            }
        }
    }
}

fun main(): Unit = runBlocking {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger = loggerContext.getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO
    val backendClient = BackendClient(logger)

    val email = "testmm@iainschmitt.com"
    val password = "myTestMmPassword"
    val ticker = "testTicker"
    val exchangeRequestDto = ExchangeRequestDto(email, ticker)

    // TODO tie the market initialisation to this
    val marketSize = 3

    try {
        backendClient.login(email, password)
            .flatMap { _ -> onStartup(backendClient, exchangeRequestDto) }
            .flatMap { pair ->
                var currentQuote = pair.quote
                val positions = pair.positions
                var orders = pair.orders
                logger.info("Initial quote: ${currentQuote.ticker}/${currentQuote.bid}/${currentQuote.ask}")
                logger.info("Initial position count: ${positions.count()}")

                if (positions.isNotEmpty()) {
                    if (orders.isNotEmpty()) {
                        val impliedQuote = getImpliedQuote(ticker, orders)
                        if (impliedQuote == null || impliedQuote != currentQuote) {
                            backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                                logger.error("Client failure: ${failure.first}/${failure.second}")
                                failure
                            }.map {
                                simpleLimitOrderSubmission(backendClient, email, ticker, marketSize, currentQuote)
                            }.map { currentQuote }
                        } else {
                            Either.Right(currentQuote)
                        }

                    } else {
                        backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                            logger.error("Client failure: ${failure.first}/${failure.second}")
                            failure
                        }.flatMap {
                            simpleLimitOrderSubmission(backendClient, email, ticker, marketSize, currentQuote)
                        }.map { currentQuote }
                    }
                } else {
                    logger.warn("No positions, market maker cannot proceed")
                    Either.Left(ClientFailure(-1, "No positions, market maker cannot proceed"))
                }
            }.mapLeft { error ->
                logger.error("Error: $error")
                val logoutSuccess = backendClient.logout()
                logger.info("Logout successful: $logoutSuccess")
            }.onRight { a: Quote ->
                launch {
                    backendClient.connectWebSocket(
                        email = email,
                        onOpen = { logger.info("WebSocket connection opened") },
                        onQuote = { println("Received message: $it") },
                        onClose = { code, reason -> logger.error("Connection closed: $code, $reason") }
                    )
                }
            }

    } catch (e: Exception) {
        logger.error("Error: ${e.message}")
    } finally {
        backendClient.close()
    }
}

//TODO move this to backend at some point, this is terribly unoptimised for what we're trying to do
private fun getImpliedQuote(ticker: Ticker, orders: List<OrderBookEntry>): Quote? {
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

private suspend fun simpleLimitOrderSubmission(
    backendClient: BackendClient,
    email: String,
    ticker: String,
    startingMarketSize: Int,
    currentQuote: Quote
): Either<ClientFailure, LimitOrderResponse> {
    return backendClient.postLimitOrderRequest(
        LimitOrderRequest(
            email = email,
            ticker = ticker,
            size = startingMarketSize,
            tradeType = TradeType.SELL,
            price = currentQuote.ask
        )
    ).flatMap {
        backendClient.postLimitOrderRequest(
            LimitOrderRequest(
                email = email,
                ticker = ticker,
                size = startingMarketSize,
                tradeType = TradeType.BUY,
                price = currentQuote.bid
            )
        )
    }
}
