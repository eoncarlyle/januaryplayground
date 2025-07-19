import arrow.core.Either
import arrow.core.Some
import arrow.core.constant
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.SimplePropertiesLoader
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.toKafkaSSLConfig
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Empty args")
        exitProcess(1)
    }

    val db = DatabaseHelper(args[0])

    val secure =
        when (args[1]) {
            "insecure" -> false
            "secure" -> true
            else -> {
                println("Invalid `cookieSecure`")
                exitProcess(1)
            }
        }

    val maybeConfig = SimplePropertiesLoader.loadFromResource("application.properties")

    val config =
        when (maybeConfig) {
            is Some -> maybeConfig.value.toKafkaSSLConfig()
            else -> {
                println("Kafk configuration must be provided")
                exitProcess(1)
            }
        }

    val app = Backend(db, config, secure)
    app.run()
}

