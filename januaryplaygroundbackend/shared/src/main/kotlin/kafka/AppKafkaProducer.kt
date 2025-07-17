package com.iainschmitt.januaryplaygroundbackend.shared.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer
import java.util.*

class AppKafkaProducer(
    sslConfig: KafkaSSLConfig
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
    }

    fun cleanup() {
        producer.close()
    }
}