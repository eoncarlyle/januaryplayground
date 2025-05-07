import arrow.core.Either
import arrow.core.flatMap
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import com.iainschmitt.januaryplaygroundbackend.shared.*

suspend fun onStartup(backendClient: BackendClient, email: String, password: String, ticker: Ticker): Either<ClientFailure, Pair<Quote, List<PositionRecord>>> {
    val maybeQuote = backendClient.getQuote(email, password)
    val maybePositions = backendClient.getLongPositions(email, ticker)
    return maybeQuote.flatMap { quote -> maybePositions.map { position -> Pair(quote, position) } }
}

fun main(): Unit = runBlocking {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger = loggerContext.getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO
    val backendClient = BackendClient(logger)

    val email = "testmm@iainschmitt.com"
    val password = "myTestMmPassword"

    try {
        backendClient.login(email, password)
            .flatMap { _ -> onStartup(backendClient, email, password, ticker = "testTicker") }
            .map { pair ->
                var currentQuote = pair.first
                val positions = pair.second
                logger.info("Initial quote: ${currentQuote.ticker}/${currentQuote.bid}/${currentQuote.ask}")
                logger.info("Initial position count: ${positions.count()}")

                if (positions.count() > 0) {
                    //If positions out-of-quote, cancel all orders and resubmit limit orders in line
                    //Otherwise do nothing
                } else {
                    //Create the positions, need to be creative with initalisation
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
