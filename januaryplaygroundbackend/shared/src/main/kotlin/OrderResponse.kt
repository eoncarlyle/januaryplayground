package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.Either
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.util.concurrent.Semaphore

typealias OrderFailure = Pair<OrderFailureCode, String>
typealias SemaphoreResult<T> = Either<T, Semaphore>
typealias OrderResult<T> = Either<OrderFailure, T>
typealias AllOrderCancelFailure = Pair<AllOrderCancelFailureCode, String>
typealias OrderCancelResult<L, R> = Either<L, R>

interface OrderCancel {
    val orderId: Int
    val email: String
    val type: String
}

interface Queueable {
    val exchangeSequenceTimestamp: Long;
}

// Only needed for market and fill-or-kill
interface IOrderAcknowledged : Order {
    val orderId: Long
}

data class OrderAcknowledged(
    override val ticker: Ticker,
    override val orderId: Long,
    override val exchangeSequenceTimestamp: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String,
) : IOrderAcknowledged, LimitOrderResponse {
    override val subtype: String = "orderAcknowledged"
}

interface IOrderFilled : Order {
    val positionId: Long
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "subtype", include = JsonTypeInfo.As.PROPERTY)
@JsonSubTypes(
    JsonSubTypes.Type(
        value = OrderFilled::class, name="orderFilled"
    ),
)
interface MarketOrderResponse: Queueable

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "subtype", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes(
    JsonSubTypes.Type(
        value = OrderAcknowledged::class, name="orderAcknowledged"
    ),
    JsonSubTypes.Type(
        value = OrderFilled::class, name="orderFilled"
    ),
    JsonSubTypes.Type(
        value = OrderPartiallyFilled::class, name="orderPartiallyFilled"
    )
)
interface LimitOrderResponse: Queueable {
    val subtype: String
}

data class OrderFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    override val exchangeSequenceTimestamp: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String,
) : IOrderFilled, MarketOrderResponse, LimitOrderResponse {
    override val subtype: String = "orderFilled"
}

data class OrderPartiallyFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    override val exchangeSequenceTimestamp: Long,
    val restingOrderId: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String,
): IOrderFilled, LimitOrderResponse {
    override val subtype: String = "orderPartiallyFilled"
}

enum class OrderFailureCode {
    MARKET_CLOSED,
    UNKNOWN_TICKER,
    UNKNOWN_USER,
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_SHARES, // Market, FOK
    INTERNAL_ERROR,
    NOT_IMPLEMENTED
}

enum class SingleOrderCancelFailureCode {
    UNKNOWN_ORDER,
    UNKNOWN_TRADER,
    ORDER_FILLED,
    INTERNAL_ERROR
}

enum class AllOrderCancelFailureCode {
    UNKNOWN_TICKER,
}



sealed class AllOrderCancelResponse: Queueable {
    data class FilledOrdersCancelled(
        val ticker: Ticker,
        val orders: Int, override val exchangeSequenceTimestamp: Long
    ) : AllOrderCancelResponse()

    data class NoOrdersCancelled(override val exchangeSequenceTimestamp: Long): AllOrderCancelResponse()
}
