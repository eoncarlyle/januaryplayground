import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.CredentialsDto
import com.iainschmitt.januaryplaygroundbackend.shared.CreditTransferDto
import com.iainschmitt.januaryplaygroundbackend.shared.Ticker
import com.iainschmitt.januaryplaygroundbackend.shared.TradeType
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaConsumer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.KafkaSSLConfig
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.math.log

private class OrchestratedNoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val trader = NoiseTrader(email, password, ticker, logger, tradeTypeState)

    fun main() = runBlocking {
    }
}

class Orchestrator(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val kafkaConfig: KafkaSSLConfig,
    private val logger: Logger
) {
    private val consumer = AppKafkaConsumer(kafkaConfig, "test-consumer-group")
    private val backendClient = BackendClient(logger)

    fun main() = runBlocking {
        either {
            backendClient.login(email, password).bind()
            backendClient.postOrchestratorLiquidateAll().bind()

        }

        consumer.startConsuming(listOf("orchestrator")) { messageProcessor(it) }
    }

    private fun messageProcessor(record: ConsumerRecord<String, String>) {
        record.value().deserializeEither<CreditTransferDto>()
            .fold({ logger.error(it) }, { dto ->
                if (dto.targetUserEmail == email) {
                    // 1) Signup user with <orchestratorEmail>_<timestamp>@iainschmitt.com, random password
                    // 2) Add the coroutine started from `main` to the 
                }
            })
    }
}