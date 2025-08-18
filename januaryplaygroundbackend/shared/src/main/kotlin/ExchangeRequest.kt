package com.iainschmitt.januaryplaygroundbackend.shared

interface OrderRequest: Order {
    val type: String
    override val email: String
    override val ticker: Ticker
    override val size: Int
    override val tradeType: TradeType
    override val orderType: OrderType
}

data class MarketOrderRequest(
    // This is no longer neccesary, I think
    override val type: String = "incomingOrder",
    override val email: String,
    override val ticker: Ticker,
    override val size: Int,
    override val tradeType: TradeType,
    // This should not be configurable
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

//This needs to be renamed to `SingleTickerExchangeRequestDto`
data class ExchangeRequestDto(
    val email: String,
    val ticker: Ticker,
)

data class MultiTickerExchangeRequestDto(
    val email: String,
    val ticker: List<Ticker>,
)

data class BalanceRequestDto(
    val userEmail: String,
)
