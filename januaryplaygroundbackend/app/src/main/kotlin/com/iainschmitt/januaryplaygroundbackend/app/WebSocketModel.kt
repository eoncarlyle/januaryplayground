package com.iainschmitt.januaryplaygroundbackend.app

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.websocket.WsContext
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

class CredentialsDto(val email: String, val password: String)

enum class WebSocketResponseStatus(val code: Int) {
    SUCCESS(200),
    CREATED(201),
    ACCEPTED(202), // Recieved but not acted upon
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    ERROR(500)
}

// Will have fields added to it
class WsUserMapRecord(val token: String?, val email: String?, val authenticated: Boolean)

typealias WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>

enum class WebSocketLifecycleOperation {
    @JsonAlias("authenticate")
    AUTHENTICATE,
    @JsonAlias("close")
    CLOSE
}

// It isn't per se neccessary to have all of this - the context realistically will have all that we
// actually need.
// However, being able to log this out will almost certainly be helpful

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(
        value = IncomingSocketLifecycleMessage::class,
        name = "incomingLifecycle"
    ),
    JsonSubTypes.Type(value = MarketOrderRequest::class, name = "incomingOrder")
)
interface WebSocketMessage {
    val type: String // Needed for deserialisation
    val email: String?
}

interface OutgoingWebSocketMessage : WebSocketMessage {
    val webSocketResponseStatus: WebSocketResponseStatus
}

data class OutgoingError(
    override val webSocketResponseStatus: WebSocketResponseStatus,
    override val email: String?,
    val errorDescription: String
) : OutgoingWebSocketMessage {
    override val type = "Error"
}

data class IncomingSocketLifecycleMessage(
    override val email: String,
    val token: String,
    val operation: WebSocketLifecycleOperation
) : WebSocketMessage {
    override val type: String = "incomingLifecycle"
}

data class OutgoingLifecycleMessage<T>(
    override val email: String?,
    val operation: WebSocketLifecycleOperation,
    override val webSocketResponseStatus: WebSocketResponseStatus,
    val body: T,
) : OutgoingWebSocketMessage {
    override val type: String = "outgoingLifecycle"
}

data class OutgoingMarketLifecycle(
    override val type: String = "incomingLifecycle",
    override val email: String,
    val operation: WebSocketLifecycleOperation
) : WebSocketMessage

data class OutgoingOrderBook(
    override val type: String = "outgoingOrderBook",
    override val email: String,
    override val bid: Map<BigDecimal, List<Order>>,
    override val ask: Map<BigDecimal, List<Order>>,
    override val publishedTick: Long
) : OrderBookRecord, WebSocketMessage
