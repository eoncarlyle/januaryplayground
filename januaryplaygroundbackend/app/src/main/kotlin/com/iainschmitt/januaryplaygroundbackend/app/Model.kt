package com.iainschmitt.januaryplaygroundbackend.app

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.websocket.WsContext
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class CredentialsDto(val email: String, val password: String)

enum class WebSocketStatus(val code: Int) {
    SUCCESS(200),
    CREATED(201),
    ACCEPTED(202), // Recieved but not acted upon
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    ERROR(500)
}

interface WebSocketResponse<T> {
    val code: Int
    val body: T
}

class WebSocketResponseImpl<T>(
    override val code: Int,
    override val body: T
) : WebSocketResponse<T>

// Will have fields added to it
class WsUserMapRecord(val token: String?, val email: String?, val authenticated: Boolean)
typealias WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>

enum class LifecycleOperation {
    @JsonAlias("authenticate")
    AUTHENTICATE,

    @JsonAlias("close")
    CLOSE;
}

// It isn't per se neccessary to have all of this - the context realistically will have all that we actually need.
// However, being able to log this out will almost certainly be helpful

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = SocketLifecycleMessage::class, name = "lifecycle")
)

interface WebSocketMessage {
    val type: String //Needed for deserialisation?
    val token: String
    val email: String
}

data class SocketLifecycleMessage(
    override val type: String = "lifecycle",
    override val token: String,
    override val email: String,
    val operation: LifecycleOperation
) : WebSocketMessage

data class IncomingOrderRequest(
    override val type: String = "incomingOrder",
    override val token: String,
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType
) : WebSocketMessage, Order

data class IncomingOrderCancelRequest(
    override val type: String = "incomingOrderCancel",
    override val token: String,
    override val email: String,
    override val orderId: UUID
) : WebSocketMessage, OrderCancelRequest

data class OutgoingOrderAcknowledged(
    override val type: String = "outgoingOrderAcknowledged",
    override val token: String,
    override val email: String,
    override val orderId: UUID,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val acknowledgedTick: Long
) : WebSocketMessage, OrderAcknowledged
data class OutgoingOrderFilled(
    override val type: String = "outgoingOrderFilled",
    override val token: String,
    override val email: String,
    override val orderId: UUID,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val filledTick: Long
) : WebSocketMessage, OrderFilled

data class OutgoingOrderFailed(
    override val type: String = "outgoingOrderCancelled",
    override val token: String,
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderFailedCode: OrderFailedCode,
    override val failedTick: Long
) : WebSocketMessage, OrderFailed

data class OutgoingOrderCancelConfirmed(
    override val type: String = "outgoingOrderCancel",
    override val token: String,
    override val email: String,
    override val orderId: UUID,
    override val confirmedTick: Long
) : WebSocketMessage, OrderCancelConfirmed

data class OutgoingOrderCancelFailed(
    override val type: String = "outgoingOrderCancelFailed",
    override val token: String,
    override val email: String,
    override val orderId: UUID,
    override val orderCancelFailedCode: OrderCancelFailedCode,
    override val failedTick: Long
) : WebSocketMessage, OrderCancelFailed

data class OutgoingOrderBook(
    override val type: String = "outgoingOrderBook",
    override val token: String,
    override val email: String,
    override val bid: Map<BigDecimal, List<Order>>,
    override val ask: Map<BigDecimal, List<Order>>,
    override val publishedTick: Long
) : WebSocketMessage, OrderBookRecord

data class OutgoingMarketLifecycle(
    override val type: String = "lifecycle",
    override val token: String,
    override val email: String,
    val operation: LifecycleOperation
): WebSocketMessage
