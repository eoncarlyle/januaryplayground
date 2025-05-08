import arrow.core.Either
import arrow.core.flatMap
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import com.iainschmitt.januaryplaygroundbackend.shared.*

private data class StartingState(
    val quote: Quote,
    val positions: List<PositionRecord>,
    val orders: List<OrderBook>
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
    try {
        backendClient.login(email, password)
            .flatMap { _ -> onStartup(backendClient, exchangeRequestDto) }
            .map { pair ->
                var currentQuote = pair.quote
                val positions = pair.positions
                var orders = pair.orders
                logger.info("Initial quote: ${currentQuote.ticker}/${currentQuote.bid}/${currentQuote.ask}")
                logger.info("Initial position count: ${positions.count()}")

                // If no positions, can't be a market maker (can't sell what you don't have),
                // this can be manual for now, but later on there should be some endpoint
                // that initialises market makers by ticker. This should probably be different
                // than initialisation, as the operation to initialise is by ticker
                // The order book should be sorted (and only have a buy and sell), there
                // needs to be some massaging of the orders (and Dto really should reflect that)

                // If the orders are in line with the quote, then don't do anything. Otherwise
                // must destroy all and orders and re-submit

                if (positions.count() > 0) {
                } else {
                }

                launch {
                    backendClient.connectWebSocket(
                        email = email,
                        onOpen = { logger.info("WebSocket connection opened") },
                        onQuote = { println("Received message: $it") },
                        onClose = { code, reason -> println("Connection closed: $code, $reason") }
                    )
                }
            }.mapLeft { error ->
                logger.error("Error: $error")

                val logoutSuccess = backendClient.logout()
                logger.info("Logout successful: $logoutSuccess")
            }

    } catch (e: Exception) {
        logger.error("Error: ${e.message}")
    } finally {
        backendClient.close()
    }
}
