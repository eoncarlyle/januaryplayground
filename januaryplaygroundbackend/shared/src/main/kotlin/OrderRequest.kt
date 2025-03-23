package com.iainschmitt.januaryplaygroundbackend.shared

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
    return if (this.tradeType == TradeType.BUY) 1 else -1
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
