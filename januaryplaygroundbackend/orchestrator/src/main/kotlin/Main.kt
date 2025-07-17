import arrow.core.raise.option
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.*
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.*
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.microseconds
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

        val producerJob = scope.launch {
            produceMessages(config)
        }
        val consumerJob = scope.launch {
            consumeMessages(config)
        }
        joinAll(producerJob, consumerJob)
    }.onNone { exitProcess(1) }
}

fun consumeMessages(config: KafkaSSLConfig) {
    val consumer = AppKafkaConsumer(config, "test-consumer-group")
    val processor = SimpleMessageProcessor()

    try {
        consumer.startConsuming(listOf("diagnostic")) { record ->
            processor.processUserEvent(record)
        }
    } catch (e: Exception) {
        println("Consumer error: ${e.message}")
    } finally {
        consumer.cleanup()
    }
}

suspend fun produceMessages(config: KafkaSSLConfig) {
    val producer = AppKafkaProducer(config)

    try {
        while (true) {
            producer.sendSync("diagnostic", "time", System.currentTimeMillis().toString())
            producer.flush()
        }
    } catch (e: Exception) {
        println("Error sending messages: ${e.message}")
        e.printStackTrace()
    } finally {
        producer.cleanup()
    }
}