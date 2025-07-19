import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.CredentialsDto
import com.iainschmitt.januaryplaygroundbackend.shared.CreditTransferDto
import com.iainschmitt.januaryplaygroundbackend.shared.Ticker
import com.iainschmitt.januaryplaygroundbackend.shared.TradeType
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaConsumer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.KafkaSSLConfig
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private class OrchestratedNoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val trader = NoiseTrader(email, password, ticker, logger, tradeTypeState)

    fun main() {
        trader.main()
    }
}

class Orchestrator(kafkaConfig: KafkaSSLConfig) {
    private val consumer = AppKafkaConsumer(kafkaConfig, "test-consumer-group")
    private val logger by lazy { LoggerFactory.getLogger(Orchestrator::class.java) }

    fun main() {
        consumer.startConsuming(listOf("orchestrator")) { messageProcessor(it) }
    }

    private fun messageProcessor(record: ConsumerRecord<String, String>) {
        either {
            val creditTransferDto = record.value().deserializeEither<CreditTransferDto>().bind()
        }
    }
}