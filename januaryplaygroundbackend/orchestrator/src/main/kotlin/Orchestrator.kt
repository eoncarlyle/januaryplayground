import arrow.core.Either
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaConsumer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.KafkaSSLConfig
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import kotlinx.coroutines.*
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.log

private class OrchestratedNoiseTrader(
    email: String,
    password: String,
    ticker: Ticker,
    logger: Logger,
    tradeTypeState: TradeType = TradeType.BUY
) {
    // Thing about how the logger could be differentiated
    private val noiseTrader = NoiseTrader(email, password, ticker, logger, tradeTypeState)

    fun main(onExit: suspend () -> Unit) = runBlocking {
        Either.catch {
            noiseTrader.main()
        }.mapLeft { onExit() }
    }
}

class Orchestrator(
    private val orchestratorEmail: String,
    private val password: String,
    private val ticker: Ticker,
    kafkaConfig: KafkaSSLConfig, //Note: for Kotlin explainer to group, talk about not having the `val` here
) {
    private val logger by lazy { LoggerFactory.getLogger(this::class.java) }

    private val consumer = AppKafkaConsumer(kafkaConfig, "test-consumer-group")
    private val backendClient = BackendClient(logger)
    private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val defaultStartingCredits = 150


    suspend fun main() {
        either {
            backendClient.login(orchestratorEmail, password).bind()
            backendClient.postOrchestratorLiquidateAll(LiquidateAllOrchestratedUsersDto(orchestratorEmail)).bind()
            relaunchAllPossibleNoiseTraders()
        }

        withContext(Dispatchers.IO) {
            val kafkaConsumerThread = Thread {
                consumer.startConsuming(listOf("orchestrator")) { messageProcessor(it) }
            }
            kafkaConsumerThread.start()
            kafkaConsumerThread.join()
        }
    }

    private suspend fun relaunchAllPossibleNoiseTraders() {
        backendClient.getUserBalance(orchestratorEmail).map { response ->
            val noiseTraderCount = response.balance / defaultStartingCredits
            logger.info("Attempt at launching $noiseTraderCount noise traders")
            repeat(response.balance / defaultStartingCredits) {
                launchNoiseTrader(defaultStartingCredits)
            }
        }.mapLeft { logger.error(it.toString()) }
    }

    private fun launchNoiseTrader(creditAmount: Int) {
        val noiseTraderEmail = "${System.currentTimeMillis()}_${generateRandomHash()}_${orchestratorEmail}"
        val noiseTraderPassword = generateSecurePassword()
        orchestratorScope.launch {
            either {
                logger.info(orchestratorEmail)
                backendClient.postSignUpOrchestrated(
                    OrchestratedCredentialsDto(
                        orchestratorEmail,
                        noiseTraderEmail,
                        noiseTraderPassword,
                        creditAmount
                    )
                ).bind()

                logger.info("Launching noise trader $noiseTraderEmail")

                OrchestratedNoiseTrader(
                    noiseTraderEmail,
                    noiseTraderPassword,
                    ticker,
                    logger
                ).main {
                    backendClient.postOrchestratorLiquidateSingle(
                        LiquidateOrchestratedUserDto(
                            orchestratorEmail,
                            noiseTraderEmail
                        )
                    )
                    relaunchAllPossibleNoiseTraders()
                }
            }.onLeft { error -> logger.error("Launch error: $error") }
        }
    }

    private fun generateSecurePassword(length: Int = 16): String =
        generateRandomSequence("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*", length)


    private fun generateRandomHash(): String =
        generateRandomSequence("abcdefghijklmnopqrstuvwxyz0123456789", 6)

    private fun generateRandomSequence(characters: String, length: Int = 16): String {
        val random = SecureRandom()
        val password = StringBuilder()

        repeat(length) {
            password.append(characters[random.nextInt(characters.length)])
        }

        return password.toString()
    }

    // Note: didn't work without coroutine scope because of the following
    // 'Unresolved reference. None of the following candidates is applicable because of receiver type mismatch:'
    private fun messageProcessor(record: ConsumerRecord<String, String>) {
        either {
            logger.info("--------Message Producer-------")
            val dto = record.value().deserializeEither<CreditTransferDto>().bind()
            launchNoiseTrader(dto.creditAmount)

            if (dto.targetUserEmail == orchestratorEmail) {
                logger.info("Inbound credit transfer for this orchestrator")
            } else {
                logger.info("Credit transfer dto received for ${dto.targetUserEmail}")
            }
        }
    }
}
