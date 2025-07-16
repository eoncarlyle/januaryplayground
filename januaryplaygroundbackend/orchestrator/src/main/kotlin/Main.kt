import kotlin.system.exitProcess

fun b() {
    val sslConfig: KafkaSSLConfig = PropertiesDeserializer.deserializeFromResource("client-config.properties")

    val producer = AppKafkaProducer(sslConfig)

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutdown signal received, stopping producer...")
        producer.cleanup()
    })

    try {
        while (true) {
            producer.sendSync("diagnostic", "time", System.currentTimeMillis().toString())
            producer.flush()
            Thread.sleep(1000)
        }

    } catch (e: Exception) {
        println("Error sending messages: ${e.message}")
        e.printStackTrace()
    } finally {
        producer.cleanup()
    }
}


fun main() {
    // Load the configuration
    val config: KafkaSSLConfig = PropertiesDeserializer.deserializeFromResource("client-config.properties")
    // Use with Kafka consumer/producer

    val consumer = AppKafkaConsumer(config, "test-consumer-group")
    val processor = SimpleMessageProcessor()

    println("Bootstrap servers: ${config.bootstrapServers}")
    println("SSL keystore location: ${config.sslKeystoreLocation}")

    Runtime.getRuntime().addShutdownHook(Thread {
        println("Shutdown signal received, stopping consumer...")
        consumer.cleanup()
        exitProcess(0)
    })

    Thread {
        b()
    }.apply {
        isDaemon = true
        name = "producer-thread"
        start()
    }

    // Consumer thread
    Thread {
        try {
            consumer.startConsuming(listOf("diagnostic")) { record ->
                processor.processUserEvent(record)
            }
        } catch (e: Exception) {
            println("Consumer error: ${e.message}")
        }
    }.apply {
        isDaemon = true
        name = "consumer-thread"
        start()
    }

    Thread.currentThread().join()
}