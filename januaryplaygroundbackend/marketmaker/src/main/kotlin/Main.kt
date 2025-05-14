import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory

fun main(): Unit = runBlocking {

    val email = "testmm@iainschmitt.com"
    val password = "myTestMmPassword"
    val ticker = "testTicker"
    val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO

    MarketMaker(email, password, ticker, logger).main()
}
