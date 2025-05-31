import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory

fun main() {
    val email = "noise1@iainschmitt.com"
    val password = "noisePassword"
    val ticker = "testTicker"
    val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO

    NoiseTrader(email, password, ticker).main()
}
