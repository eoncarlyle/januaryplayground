import arrow.core.raise.option
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.LoggerContext
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

suspend fun main(args: Array<String>) {
    option {

        val properties = SimplePropertiesLoader.loadFromResource("application.properties").bind()
        val config = properties.toKafkaSSLConfig()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        if (args.size != 3) {
            println("Illegal arguments $args" )
            exitProcess(1)
        } else {
            val email = args[0]
            val password = args[1]
            val ticker = args[2]

            val consumerJob = scope.launch {
                Orchestrator(email, password, ticker, config).main()
            }
            joinAll(consumerJob)
        }
    }.onNone {
         println("Error: no Kafka properties specified")
        exitProcess(1)
    }
}
