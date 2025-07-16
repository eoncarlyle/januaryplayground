import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*
import java.util.concurrent.Future

class AppKafkaProducer(
    private val sslConfig: KafkaSSLConfig
) {
    private val producer: KafkaProducer<String, String>

    init {
        val props = sslConfig.toProperties().apply {
            put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer::class.java.name)
            put(ProducerConfig.ACKS_CONFIG, "all")
            put(ProducerConfig.RETRIES_CONFIG, "3")
            put(ProducerConfig.BATCH_SIZE_CONFIG, "16384")
            put(ProducerConfig.LINGER_MS_CONFIG, "1")
            put(ProducerConfig.BUFFER_MEMORY_CONFIG, "33554432")
            put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true")
            put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5")
            put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy")
        }

        producer = KafkaProducer(props)
    }

    // Send message synchronously (blocks until complete)
    fun sendSync(topic: String, key: String?, value: String): RecordMetadata {
        val record = ProducerRecord(topic, key, value)
        return try {
            val metadata = producer.send(record).get()
            println("Message sent successfully:")
            printSendResult(topic, key, value, metadata)
            metadata
        } catch (e: Exception) {
            throw e
        }
    }

    // Send message with custom headers
    fun sendWithHeaders(
        topic: String,
        key: String?,
        value: String,
        headers: Map<String, String>
    ): Future<RecordMetadata> {
        val record = ProducerRecord(topic, key, value)

        // Add headers to the record
        headers.forEach { (headerKey, headerValue) ->
            record.headers().add(headerKey, headerValue.toByteArray())
        }

        return producer.send(record) { metadata, exception ->
            if (exception != null) {
                println("Failed to send message with headers: ${exception.message}")
            } else {
                println("Message with headers sent successfully:")
                printSendResult(topic, key, value, metadata)
                println("   Headers: $headers")
            }
        }
    }

    fun sendWithTimestamp(
        topic: String,
        key: String?,
        value: String,
        timestamp: Long
    ): Future<RecordMetadata> {
        val record = ProducerRecord(topic, null, timestamp, key, value)
        return producer.send(record) { metadata, exception ->
            if (exception != null) {
                println("Failed to send timestamped message: ${exception.message}")
            } else {
                println("Timestamped message sent successfully:")
                printSendResult(topic, key, value, metadata)
                println("   Custom timestamp: ${Date(timestamp)}")
            }
        }
    }

    private fun printSendResult(topic: String, key: String?, value: String, metadata: RecordMetadata) {
        println("┌─────────────────────────────────────────────")
        println("│ Topic: ${metadata.topic()}")
        println("│ Partition: ${metadata.partition()}")
        println("│ Offset: ${metadata.offset()}")
        println("│ Timestamp: ${Date(metadata.timestamp())}")
        println("│ Key: ${key ?: "null"}")
        println("│ Value: ${value.take(100)}${if (value.length > 100) "..." else ""}")
        println("└─────────────────────────────────────────────")
    }

    fun flush() {
        producer.flush()
        println("Producer flushed - all pending messages sent")
    }

    fun cleanup() {
        try {
            producer.close()
            println("Producer closed gracefully")
        } catch (e: Exception) {
            println("Error closing producer: ${e.message}")
        }
    }
}