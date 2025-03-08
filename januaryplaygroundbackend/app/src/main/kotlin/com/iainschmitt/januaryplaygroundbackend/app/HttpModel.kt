package com.iainschmitt.januaryplaygroundbackend.app

import com.iainschmitt.januaryplaygroundbackend.shared.*

// Probably not neccesary with how this is shaping up
// @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
// @JsonSubTypes(
//     JsonSubTypes.Type(value = IncomingOrderRequest::class, name = "incomingOrder"),
//     JsonSubTypes.Type(value = OutgoingOrderAcknowledged::class, name = "outgoingOrderAcknowledged"),
//     JsonSubTypes.Type(value = OutgoingOrderFilled::class, name = "outgoingOrderFilled"),
// )
interface HttpOrderBody: Order {
    override val email: String
    val type: String
}

// Probably not neccesary with how this is shaping up
//@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
//@JsonSubTypes(
//    JsonSubTypes.Type(value = IncomingOrderCancelRequest::class, name = "incomingOrderCancel"),
//    JsonSubTypes.Type(value = OutgoingOrderFailed::class, name = "outgoingOrderCancelled"),
//    JsonSubTypes.Type(value = OutgoingOrderCancelConfirmed::class, name = "outgoingOrderCancel"),
//    JsonSubTypes.Type(value = OutgoingOrderCancelFailed::class, name = "outgoingOrderCancelFailed"),
//)
interface HttpOrderCancelledBody: OrderCancel {
    override val email: String
    val type: String
}

data class IncomingOrderRequest(
    override val type: String = "incomingOrder",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType,
) : HttpOrderBody

data class OutgoingOrderAcknowledged(
    override val type: String = "outgoingOrderAcknowledged",
    override val email: String,
    override val orderId: Int,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val acknowledgedTick: Long
) : IOrderAcknowledged, HttpOrderBody

data class OutgoingIOrderFilled(
    override val type: String = "outgoingOrderFilled",
    override val email: String,
    override val positionId: Int,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val filledTime: Long
) : IOrderFilled, HttpOrderBody

data class IncomingOrderCancelRequest(
    override val type: String = "incomingOrderCancel",
    override val email: String,
    override val orderId: Int,
) : OrderCancelRequest, HttpOrderCancelledBody

data class OutgoingOrderFailed(
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val orderFailedCode: OrderFailedCode,
    override val failedTick: Long
) : OrderFailed

data class OutgoingOrderCancelConfirmed(
    override val type: String = "outgoingOrderCancel",
    override val email: String,
    override val orderId: Int,
    override val confirmedTick: Long
) : OrderCancelConfirmed, HttpOrderCancelledBody

data class OutgoingOrderCancelFailed(
    override val orderId: Int,
    override val orderCancelFailedCode: OrderCancelFailedCode,
    override val failedTick: Long
) : OrderCancelFailed
