import arrow.core.Some
import com.iainschmitt.januaryplaygroundbackend.shared.SimplePropertiesLoader
import com.iainschmitt.januaryplaygroundbackend.shared.SimplePropertiesLoader.toKafkaSSLConfig
import com.iainschmitt.januaryplaygroundbackend.shared.SimplePropertiesLoader.toKafkaTopicsConfig
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Empty args")
        exitProcess(1)
    }

    val secure =
        when (args[1]) {
            "insecure" -> false
            "secure" -> true
            else -> {
                println("Invalid `cookieSecure`")
                exitProcess(1)
            }
        }

    val (applicationConfig, kafkaTopicsConfig) =
        when (val maybeConfig = SimplePropertiesLoader.loadFromFile(args[0])) {
            is Some -> maybeConfig.value.toKafkaSSLConfig() to maybeConfig.value.toKafkaTopicsConfig()
            else -> {
                println("Kafka configuration must be provided")
                exitProcess(1)
            }
        }

    val db = DatabaseHelper(applicationConfig.database)

    val app = Backend(db, applicationConfig, kafkaTopicsConfig, secure)
    app.run()
}

