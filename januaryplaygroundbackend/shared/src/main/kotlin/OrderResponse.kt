package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.Either

typealias OrderFailure = Pair<OrderFailureCode, String>
typealias OrderResult<T> = Either<OrderFailure, T>
typealias OrderCancelResult<L, R> = Either<Pair<L, String>, R>
interface OrderCancel {
    val orderId: Int
    val email: String
    val type: String
}

// Only needed for market and fill-or-kill
interface IOrderAcknowledged : Order {
    val orderId: Long
    val receivedTick: Long // Will need to include/reference any partial execution!
}

data class OrderAcknowledged(
    override val ticker: Ticker,
    override val orderId: Long,
    override val receivedTick: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String
) : IOrderAcknowledged, LimitOrderResponse

// Can have multiple with a singe
interface IOrderFilled : Order {
    val positionId: Long
    val filledTick: Long
}

interface MarketOrderResponse {

}

interface LimitOrderResponse {

}

data class OrderFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    override val filledTick: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String
) : IOrderFilled, MarketOrderResponse, LimitOrderResponse

data class OrderPartiallyFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    val restingOrderId: Long,
    override val filledTick: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String
): IOrderFilled, LimitOrderResponse

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
    INSUFFICIENT_SHARES
}

data class AllOrderCancelResponse(
    val ticker: Ticker,
    val cancelledTick: Long,
    val orders: Int
)