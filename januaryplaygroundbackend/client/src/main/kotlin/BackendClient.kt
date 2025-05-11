import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import org.slf4j.Logger
import arrow.core.Either
import arrow.core.Option
import com.fasterxml.jackson.core.type.TypeReference
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.ktor.client.statement.*
import kotlin.reflect.KSuspendFunction1


typealias ClientFailure = Pair<Int, String>

class BackendClient(
    private val logger: Logger,
    private val baseurl: String = "127.0.0.1",
    private val port: Int = 7070
) {
    private val httpBaseurl = "http://$baseurl:$port"
    private val objectMapper = jacksonObjectMapper()

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
        install(HttpCookies)
        install(WebSockets)
    }

    suspend fun login(email: String, password: String): Either<ClientFailure, Map<String, String>> {
        logger.info("Logging in user: $email")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "login")
            }
            contentType(ContentType.Application.Json)
            setBody(CredentialsDto(email, password))
        }

        return when (response.status) {
            HttpStatusCode.OK -> Either.Right(response.body())
            else -> Either.Left(ClientFailure(response.status.value, "Login failed"))
        }
    }

    suspend fun evaluateAuth(): Either<ClientFailure, Map<String, Any>> {
        logger.info("Evaluating authentication")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "evaluate")
            }
        }

        return when (response.status) {
            HttpStatusCode.OK -> Either.Right(response.body())
            else -> Either.Left(ClientFailure(response.status.value, "Authentication failed"))
        }
    }

    suspend fun temporarySession(email: String): Either<ClientFailure, String> {
        logger.info("Getting temporary session for: $email")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "sessions", "temporary")
            }
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email))
        }

        return when (response.status) {
            HttpStatusCode.Created -> {
                Option.fromNullable(response.body<Map<String, String>>()["token"])
                    .toEither { ClientFailure(response.status.value, "No token in response") }
            }

            else -> Either.Left(ClientFailure(response.status.value, "Temporary session authentication failed"))
        }
    }

    suspend fun logout(): Boolean {
        logger.info("Logging out")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "logout")
            }
        }
        return response.status == HttpStatusCode.OK
    }

    private suspend inline fun <reified T, R> marketPost(
        request: T,
        vararg components: String
    ): Either<ClientFailure, R> {
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments(*components)
            }
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        return when (response.status) {
            HttpStatusCode.Created -> {
                Either.catch {
                    objectMapper.readValue(response.bodyAsText(), object : TypeReference<R>() {})
                }.mapLeft { ClientFailure(-1, "Could not deserialise") }
            }

            else -> Either.Left(ClientFailure(response.status.value, response.body()))
        }
    }

    public suspend fun <T, R> retry(
        argument: T,
        request: suspend (T) -> Either<ClientFailure, R>,
        maxAttempts: Int,
    ): Either<ClientFailure, R> {
        var currentAttempts = 0
        while (currentAttempts < maxAttempts) {
            currentAttempts += 1
            val result = request.invoke(argument)
            result.onLeft { error -> logger.error(error.second) }
            return when {
                result is Either.Right || currentAttempts == maxAttempts -> result
                else -> {
                    continue
                }
            }
        }
        // Not actually reachable
        return Either.Left(ClientFailure(-1, "Exhausted allowed retries"))
    }


    suspend fun signUp(credentialsDto: CredentialsDto): Either<ClientFailure, Map<String, String>> {
        return marketPost<CredentialsDto, Map<String, String>>(credentialsDto, "auth", "signup")
    }

    suspend fun getUserLongPositions(exchangeRequestDto: ExchangeRequestDto): Either<ClientFailure, List<PositionRecord>> {
        return marketPost<ExchangeRequestDto, List<PositionRecord>>(
            exchangeRequestDto,
            "exchange",
            "positions"
        )
    }

    suspend fun getUserOrders(exchangeRequestDto: ExchangeRequestDto): Either<ClientFailure, List<OrderBookEntry>> {
        return marketPost<ExchangeRequestDto, List<OrderBookEntry>>(
            exchangeRequestDto,
            "exchange",
            "orders"
        )
    }

    suspend fun getQuote(exchangeRequestDto: ExchangeRequestDto): Either<ClientFailure, Quote> {
        return marketPost<ExchangeRequestDto, Quote>(exchangeRequestDto, "exchange", "quote")
    }

    suspend fun postMarketOrderRequest(marketOrderResponse: MarketOrderRequest): Either<ClientFailure, MarketOrderResponse> {
        return marketPost(marketOrderResponse, "exchange", "orders", "market")
    }

    suspend fun postLimitOrderRequest(limitOrderRequest: LimitOrderRequest): Either<ClientFailure, LimitOrderResponse> {
        return marketPost(limitOrderRequest, "exchange", "orders", "limit")
    }

    suspend fun postAllOrderCancel(exchangeRequestDto: ExchangeRequestDto): Either<ClientFailure, AllOrderCancelResponse> {
        return marketPost(exchangeRequestDto, "exchange", "orders", "cancel_all")
    }

    suspend fun connectWebSocket(
        email: String,
        onOpen: () -> Unit = {},
        onQuote: (Quote) -> Unit = {},
        onClose: (Int, String?) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {}
    ) = coroutineScope {
        temporarySession(email).onRight { token ->
            try {
                client.webSocket(method = HttpMethod.Get, host = baseurl, path = "/ws", port = port) {
                    logger.info("WebSocket connection established")
                    onOpen()

                    val authMessage = ClientLifecycleMessage(
                        email = email, token = token, operation = WebSocketLifecycleOperation.AUTHENTICATE
                    )
                    send(Frame.Text(objectMapper.writeValueAsString(authMessage)))

                    launch {
                        try {
                            incoming.consumeEach { frame ->
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        logger.debug("Received WebSocket message: $text")
                                        Either.catch {
                                            objectMapper.readValue(text, object : TypeReference<WebSocketMessage>() {})
                                        }.mapLeft { logger.warn("Could not deserialise $text") }.onRight { message ->
                                            when (message) {
                                                is ServerLifecycleMessage -> logger.info(
                                                    objectMapper.writeValueAsString(message)
                                                )

                                                is QuoteMessage -> onQuote(message.quote)
                                                is ServerTimeMessage -> logger.info(
                                                    objectMapper.writeValueAsString(message)
                                                )

                                                else -> {
                                                    logger.warn(
                                                        "Client received something unexpected ${
                                                            objectMapper.writeValueAsString(
                                                                message
                                                            )
                                                        }"
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    else -> logger.debug("Received other frame type: {}", frame)
                                }
                            }
                        } catch (e: Exception) {
                            logger.error("Error processing WebSocket messages", e)
                            onError(e)
                        }
                    }

                    while (isActive) {
                        delay(5000)
                        try {
                            send(Frame.Ping(ByteArray(0)))
                        } catch (e: Exception) {
                            logger.error("Error sending ping", e)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("WebSocket connection error", e)
                onError(e)
            } finally {
                logger.info("WebSocket connection closed")
                onClose(1000, "Connection closed")
            }
        }
    }

    fun close() {
        client.close()
    }
}
