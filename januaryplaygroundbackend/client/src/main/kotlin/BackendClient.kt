package com.iainschmitt.januaryplaygroundbackend.marketmaker

import com.fasterxml.jackson.databind.ObjectMapper
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
import org.slf4j.LoggerFactory
import arrow.core.Either
import com.iainschmitt.januaryplaygroundbackend.shared.*

class BackendClient(private val baseurl: String = "127.0.0.1", private val port: Int = 7070) {
    private val logger = LoggerFactory.getLogger(BackendClient::class.java)
    private val httpBaseurl = "http://$baseurl:$port"
    private val objectMapper = ObjectMapper()

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            jackson()
        }
        install(HttpCookies)
        install(WebSockets)
    }

    suspend fun signUp(email: String, password: String): Either<String, Map<String, String>> {
        logger.info("Signing up user: $email")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "signup")
            }
            contentType(ContentType.Application.Json)
            setBody(CredentialsDto(email, password))
        }

        return if (response.status == HttpStatusCode.Created) {
            Either.Right(response.body())
        } else {
            Either.Left("Sign up failed with status: ${response.status}")
        }
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

    suspend fun evaluateAuth(): Map<String, Any> {
        logger.info("Evaluating authentication")
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("auth", "evaluate")
            }
        }

        return when (response.status) {
            HttpStatusCode.OK -> response.body()
            else -> mapOf("authenticated" to false)
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
                val responseBody: Map<String, String> = response.body()
                // Use an option type once you have wifi back, there's gotta be some appropriate method for this
                if (responseBody["token"] != null) {
                    return Either.Right(responseBody["token"]!!)
                } else {
                    return Either.Left("No token in response")
                }
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

    suspend fun getLongPositions(email: String, ticker: Ticker): Either<String, List<PositionRecord>> {
        val response = client.post(httpBaseurl) {
            url {
                appendPathSegments("orders")
            }
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "ticker" to ticker))
        }

        return when(response.status) {
            //Need to deserialise this properly
            HttpStatusCode.Created -> { val responseBody: List<PositionRecord> = response.body()  }
        }
    }


    suspend fun connectWebSocket(
        email: String,
        onOpen: () -> Unit = {},
        onMessage: (String) -> Unit = {},
        onClose: (Int, String?) -> Unit = { _, _ -> },
        onError: (Throwable) -> Unit = {}
    ) = coroutineScope {
        val eToken = temporarySession(email)

        eToken.onRight { token ->
            try {
                client.webSocket(method = HttpMethod.Get, host = baseurl, path = "/ws", port = port) {
                    logger.info("WebSocket connection established")
                    onOpen()

                    val authMessage = IncomingSocketLifecycleMessage(
                        email = email,
                        token = token,
                        operation = WebSocketLifecycleOperation.AUTHENTICATE
                    )
                    send(Frame.Text(objectMapper.writeValueAsString(authMessage)))

                    launch {
                        try {
                            incoming.consumeEach { frame ->
                                when (frame) {
                                    is Frame.Text -> {
                                        val text = frame.readText()
                                        logger.debug("Received WebSocket message: $text")
                                        onMessage(text)
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
