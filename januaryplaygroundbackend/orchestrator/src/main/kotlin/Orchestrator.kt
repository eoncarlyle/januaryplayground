import arrow.core.Either
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaConsumer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.KafkaSSLConfig
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import java.security.SecureRandom

private class OrchestratedNoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private val logger: Logger,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val noiseTrader = NoiseTrader(email, password, ticker, logger, tradeTypeState)

    fun main(onExit: suspend () -> Unit) = runBlocking {
        Either.catch {
            noiseTrader.main()
        }.mapLeft { onExit() }
    }
}

class Orchestrator(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    kafkaConfig: KafkaSSLConfig, //Note: for Kotlin explainer to group, talk about not having the `val` here
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

    private fun generateSecurePassword(length: Int = 16): String {
        val random = SecureRandom()
        val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val password = StringBuilder()

        repeat(length) {
            password.append(characters[random.nextInt(characters.length)])
        }

        return password.toString()
    }

    // Note: didn't work without coroutine scope because of the following
    // 'Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:'
    private suspend fun messageProcessor(record: ConsumerRecord<String, String>) = coroutineScope {
        either {
            val dto = record.value().deserializeEither<CreditTransferDto>().bind()
            if (dto.targetUserEmail == email) {
                val noiseTraderEmail = "${email}_${System.currentTimeMillis()}@iainschmitt.com"
                val noiseTraderPassword = generateSecurePassword()

                backendClient.postSignUpOrchestrated(
                    OrchestratedCredentialsDto(
                        noiseTraderEmail,
                        noiseTraderPassword,
                        dto.creditAmount
                    )
                ).bind()

                launch {
                    OrchestratedNoiseTrader(noiseTraderEmail, noiseTraderPassword, ticker, logger).main { backendClient.postOrchestratorLiquidateSingle(LiquidateOrchestratedUserDto(noiseTraderPassword)) }
                }

            } else {
                logger.info("Credit transfer dto received for ${dto.targetUserEmail}")
            }
        }
    }
}