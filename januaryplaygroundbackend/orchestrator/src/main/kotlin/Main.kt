import arrow.core.raise.option
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

class SimpleMessageProcessor {
    fun processUserEvent(record: ConsumerRecord<String, String>) {
        val timestamp = Date(record.timestamp())
        println("[$timestamp] ${record.topic()}/${record.partition()}:${record.offset()} - ${record.value()}")
    }
}

suspend fun main() {
    option {
        val properties = SimplePropertiesLoader.loadFromResource("application.properties").bind()
        val config = properties.toKafkaSSLConfig()
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val orchestrator = Orchestrator(config)

        val consumerJob = scope.launch {
            orchestrator.main()
        }
        joinAll(consumerJob)
    }.onNone {
         println("Error: no Kafka properties specified")
        exitProcess(1)
    }
}
