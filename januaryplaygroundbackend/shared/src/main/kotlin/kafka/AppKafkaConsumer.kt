package com.iainschmitt.januaryplaygroundbackend.shared.kafka

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.*

inline fun <reified T> String.deserializeEither(): Either<String, T> =
    try {
        Json.decodeFromString<T>(this).right()
    } catch (e: SerializationException) {
        (e.message ?: "Unknown serialization error").left()
    } catch (e: Exception) {
        (e.message ?: "Unknown error").left()
    }
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
                    messageProcessor(record)
                }
                consumer.commitSync()
            }
        } catch (e: Exception) {
            throw(e)
        } finally {
            consumer.close()
        }
    }
}
