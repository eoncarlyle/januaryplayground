package com.iainschmitt.januaryplaygroundbackend.app

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.iainschmitt.januaryplaygroundbackend.shared.*
import java.util.*

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = IncomingOrderRequest::class, name = "incomingOrder"),
    JsonSubTypes.Type(value = OutgoingOrderAcknowledged::class, name = "outgoingOrderAcknowledged"),
    JsonSubTypes.Type(value = OutgoingOrderFilled::class, name = "outgoingOrderFilled"),
)
interface HttpOrderBody: Order {
    override val email: String
    val type: String
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = IncomingOrderCancelRequest::class, name = "incomingOrderCancel"),
    JsonSubTypes.Type(value = OutgoingOrderFailed::class, name = "outgoingOrderCancelled"),
    JsonSubTypes.Type(value = OutgoingOrderCancelConfirmed::class, name = "outgoingOrderCancel"),
    JsonSubTypes.Type(value = OutgoingOrderCancelFailed::class, name = "outgoingOrderCancelFailed"),
)
interface HttpOrderCancelledBody: OrderCancel {
    override val email: String
    val type: String
}

data class IncomingOrderRequest(
    override val type: String = "incomingOrder",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType
) : HttpOrderBody

data class OutgoingOrderAcknowledged(
    override val type: String = "outgoingOrderAcknowledged",
    override val email: String,
    override val orderId: UUID,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val acknowledgedTick: Long
) : OrderAcknowledged, HttpOrderBody

data class OutgoingOrderFilled(
    override val type: String = "outgoingOrderFilled",
    override val email: String,
    override val orderId: UUID,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val filledTick: Long
) : OrderFilled, HttpOrderBody

data class IncomingOrderCancelRequest(
    override val type: String = "incomingOrderCancel",
    override val email: String,
    override val orderId: UUID,
) : OrderCancelRequest, HttpOrderCancelledBody

data class OutgoingOrderFailed(
    override val type: String = "outgoingOrderCancelled",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderFailedCode: OrderFailedCode,
    override val failedTick: Long
) : OrderFailed, HttpOrderBody

data class OutgoingOrderCancelConfirmed(
    override val type: String = "outgoingOrderCancel",
    override val email: String,
    override val orderId: UUID,
    override val confirmedTick: Long
) : OrderCancelConfirmed, HttpOrderCancelledBody

data class OutgoingOrderCancelFailed(
    override val type: String = "outgoingOrderCancelFailed",
    override val email: String,
    override val orderId: UUID,
    override val orderCancelFailedCode: OrderCancelFailedCode,
    override val failedTick: Long
) : OrderCancelFailed, HttpOrderCancelledBody
