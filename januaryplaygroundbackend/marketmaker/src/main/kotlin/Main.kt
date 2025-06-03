import ch.qos.logback.classic.LoggerContext
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main(args: Array<String>): Unit = runBlocking {
    val logger = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger("MainKt")
    logger.level = ch.qos.logback.classic.Level.INFO

    if (args.size != 3) {
        logger.error("Illegal arguments {}", args)
        exitProcess(1)
    } else {
        val email = args[0]
        val password = args[1]
        val ticker = args[2]

        MarketMaker(email, password, ticker, logger).main()
    }
}

