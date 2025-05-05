import arrow.core.Either
import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import com.iainschmitt.januaryplaygroundbackend.shared.*
import org.slf4j.event.Level

suspend fun onStartup(backendClient: BackendClient, email: String, password: String, ticker: Ticker) {

    val quote =  backendClient.getQuote(email, password)
    val positions = backendClient.getLongPositions(email, ticker)

    when (quote to positions) -> {
        Either.Right to Either.Right -> {

        } else -> logg

    }
}

fun main() = runBlocking {
    val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
    val logger = loggerContext.getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO
    val backendClient = BackendClient(logger)

    val email = "testmm@iainschmitt.com"
    val password = "myTestMmPassword"

    try {
        backendClient.login(email, password)
            .map { backendClient.evaluateAuth() }
            .map { backendClient.getQuote(email, password) }
            .map { backendClient }
            .map {
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
        println("Error: ${e.message}")
    } finally {
        backendClient.close()
    }
}
