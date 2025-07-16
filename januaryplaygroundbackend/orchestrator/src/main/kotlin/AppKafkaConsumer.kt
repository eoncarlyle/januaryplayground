import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.*
import kotlin.system.exitProcess

class AppKafkaConsumer(
    sslConfig: KafkaSSLConfig,
    private val groupId: String = "default-consumer-group"
) {
    private val consumer: KafkaConsumer<String, String>

    init {
        val props = sslConfig.toProperties().apply {
            put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
            put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java.name)
            put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "100")
            put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "30000")
            put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "10000")
        }

        consumer = KafkaConsumer(props)
    }

    fun startConsuming(topics: List<String>, messageProcessor: (ConsumerRecord<String, String>) -> Unit) {
        try {
            consumer.subscribe(topics)

            while (true) {
                val records: ConsumerRecords<String, String> = consumer.poll(Duration.ofMillis(1000))

                if (records.isEmpty) {
                    continue
                }

                for (record in records) {
                    try {
                        messageProcessor(record)
                        printMessageDetails(record)
                    } catch (e: Exception) {
                        println("Error processing message: ${e.message}")
                        println("   Topic: ${record.topic()}, Partition: ${record.partition()}, Offset: ${record.offset()}")
                    }
                }

                consumer.commitSync()
                println("Committed offsets")
            }
        } catch (e: Exception) {
            println("Consumer error: ${e.message}")
            e.printStackTrace()
        } finally {
            cleanup()
        }
    }

    private fun printMessageDetails(record: ConsumerRecord<String, String>) {
        println("┌─────────────────────────────────────────────")
        println("│ Topic: ${record.topic()}")
        println("│ Partition: ${record.partition()}")
        println("│ Offset: ${record.offset()}")
        println("│ Timestamp: ${Date(record.timestamp())}")
        println("│ Key: ${record.key() ?: "null"}")
        println("│ Value: ${record.value()}")
        println("└─────────────────────────────────────────────")
    }

    fun cleanup() {
        try {
            consumer.close()
            println("Consumer closed gracefully")
        } catch (e: Exception) {
            println("Error closing consumer: ${e.message}")
        }
    }
}

class SimpleMessageProcessor {
    fun processUserEvent(record: ConsumerRecord<String, String>) {
        val timestamp = Date(record.timestamp())
        println("[$timestamp] ${record.topic()}/${record.partition()}:${record.offset()} - ${record.value()}")
    }
}


