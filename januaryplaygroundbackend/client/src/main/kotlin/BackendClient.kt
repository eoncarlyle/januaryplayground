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

    suspend fun login(email: String, password: String): Either<String, Map<String, String>> {
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
            else -> Either.Left("Login failed with status: ${response.status}")
        }
    }

    suspend fun evaluateAuth(): Either<Map<String, Any>, Map<String, Any>> {
        logger.info("Evaluating authentication")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "evaluate")
            }
        }

        return when (response.status) {
            HttpStatusCode.OK -> Either.Right(response.body())
            else -> Either.Left(mapOf("authenticated" to false))
        }
    }

    suspend fun temporarySession(email: String): Either<String, String> {
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
                Option.fromNullable(response.body<Map<String, String>>()["token"]).toEither { "No token in response" }
            }

            else -> Either.Left(("Failed to get temporary session with status: ${response.status}"))
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

    private suspend inline fun <reified T, R> genericPost(request: T, vararg components: String): Either<ClientFailure, R> {
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
                }.mapLeft { ClientFailure(-1, "Could not deserialise")  }
            }

            else -> Either.Left(ClientFailure(response.status.value, response.body()))
        }
    }

    suspend fun signUp(credentialsDto: CredentialsDto): Either<ClientFailure, Map<String, String>> {
        return genericPost<CredentialsDto, Map<String, String>>(credentialsDto, "auth", "signup")
    }

    suspend fun getLongPositions(email: String, ticker: Ticker): Either<ClientFailure, List<PositionRecord>> {
        return genericPost<Map<String, String>, List<PositionRecord>>(mapOf("email" to email, "ticker" to ticker), "orders", "positions")
    }

    suspend fun getQuote(email: String, ticker: Ticker): Either<ClientFailure, Quote> {
        return genericPost<Map<String, String>, Quote>(mapOf("email" to email, "ticker" to ticker), "orders", "quote")
    }

    suspend fun postLimitOrderRequest(limitOrderRequest: LimitOrderRequest): Either<ClientFailure, LimitOrderResponse> {
        return genericPost(limitOrderRequest, "orders", "limit")
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
