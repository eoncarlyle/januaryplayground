package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.Either

interface Order {
    val ticker: Ticker;
    val tradeType: TradeType;
    val orderType: OrderType;
    val size: Int;
    val email: String;
}
interface OrderRequest: Order {
    val type: String
    override val email: String
    override val ticker: Ticker
    override val size: Int
    override val tradeType: TradeType
    override val orderType: OrderType
}

fun OrderRequest.isBuy(): Boolean {
    return tradeType.isBuy()
}

fun Order.sign(): Int {
    return if (this.tradeType == TradeType.Buy) 1 else -1
}

data class MarketOrderRequest(
    override val type: String = "incomingOrder",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType = OrderType.Market
) : OrderRequest

data class LimitOrderRequest(
    override val type: String = "incomingOrder",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    override val orderType: OrderType = OrderType.Limit,
    val price: Int
) : OrderRequest {
    fun getResizedOrder(newSize: Int): LimitOrderRequest {
        return LimitOrderRequest(
            this.type,
            this.email,
            this.ticker,
            newSize,
            this.tradeType,
            this.orderType,
            this.price
        )
    }
}

data class OrderCancelRequest(
    override val type: String = "incomingOrderCancel",
    override val email: String,
    override val orderId: Int,
) : OrderCancel

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
    val filledTime: Long
}

interface MarketOrderResponse {

}

interface LimitOrderResponse {

}

data class OrderFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    override val filledTime: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String
) : IOrderFilled, MarketOrderResponse, LimitOrderResponse

data class OrderPartiallyFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    val restingOrderId: Long,
    override val filledTime: Long,
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

typealias OrderFailure = Pair<OrderFailureCode, String>
typealias OrderResult<T> = Either<OrderFailure, T>

enum class OrderCancelFailedCode {
    UNKNOWN_ORDER,
    UNKNOWN_TRADER,
    ORDER_FILLED,
    INTERNAL_ERROR
}
