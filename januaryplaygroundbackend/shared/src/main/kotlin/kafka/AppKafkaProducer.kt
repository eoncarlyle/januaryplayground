package com.iainschmitt.januaryplaygroundbackend.shared.kafka

import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.StringSerializer

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
        return producer.send(ProducerRecord(topic, key, value)).get()
    }
}