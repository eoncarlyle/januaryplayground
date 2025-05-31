import arrow.core.Either
import arrow.core.flatMap
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.random.nextInt

class NoiseTrader(
    private val email: String,
    private val password: String,
    private val ticker: Ticker,
    private var tradeTypeState: TradeType = TradeType.BUY
) {
    private val transactionSize = 1
    private val logger by lazy { LoggerFactory.getLogger(NoiseTrader::class.java) }
    private val exchangeRequestDto = ExchangeRequestDto(email, ticker)

    private val backendClient = BackendClient(logger)

    fun main(): Unit = runBlocking {
        Either.catch {
            backendClient.login(email, password)
            either {
                val startingState = backendClient.getStartingState(exchangeRequestDto).bind()
                handleStartingState(startingState).bind()
            }
                .onRight { _ ->
                    launch {
                        while (true) {
                            delay(1.seconds)
                            if (Random.nextInt(0..5) == 0) {
                                backendClient.postMarketOrderRequest(
                                    MarketOrderRequest(
                                        email = email,
                                        ticker = ticker,
                                        size = transactionSize,
                                        tradeType = tradeTypeState,
                                        orderType = OrderType.Market
                                    )
                                ).onRight {
                                    if (Random.nextInt(0..1) == 0) {
                                        tradeTypeState = if (tradeTypeState.isBuy()) TradeType.SELL else TradeType.BUY
                                    }
                                }.onLeft { failure ->
                                    logger.warn("Noise trader order failed: $failure")
                                    if (failure.first == 400) {
                                        tradeTypeState = if (tradeTypeState.isBuy()) TradeType.SELL else TradeType.BUY
                                    }
                                }
                            }
                        }
                    }
                }
        }.mapLeft { throwable -> ClientFailure(-1, throwable.message ?: "Message not provided") }
            .onLeft { error ->
                logger.error("Error: $error")
                val logoutSuccess = backendClient.logout()
                logger.info("Logout successful: $logoutSuccess")
                backendClient.close()
            }
    }

    private suspend fun handleStartingState(state: StartingState): Either<ClientFailure, SafeQuote> {
        val startingQuote = state.quote
        val positions = state.positions
        val orders = state.orders

        logger.info("Initial quote: ${startingQuote.ticker}/${startingQuote.bid}/${startingQuote.ask}")
        logger.info("Initial position count: ${positions.count()}")

        if (positions.any { it.positionType != PositionType.LONG }) {
            return Either.Left(ClientFailure(-1, "Unimplemented short positions found"))
        } else {
            tradeTypeState = if (positions.any { it.positionType == PositionType.LONG }) {
                TradeType.SELL
            } else {
                TradeType.BUY
            }
        }

        return when (orders.size) {
            0 -> Either.Right(startingQuote)
            else -> backendClient.postAllOrderCancel(exchangeRequestDto).mapLeft { failure ->
                logger.error("Client failure: ${failure.first}/${failure.second}")
                failure
            }.map { startingQuote }
        }
    }
}
