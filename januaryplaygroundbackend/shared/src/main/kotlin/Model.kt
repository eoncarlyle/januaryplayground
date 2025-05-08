package com.iainschmitt.januaryplaygroundbackend.shared

import com.fasterxml.jackson.annotation.JsonAlias
import java.math.BigDecimal

class CredentialsDto(val email: String, val password: String)
typealias Ticker = String


enum class TradeType {
    //@JsonAlias("buy")
    BUY,

    //@JsonAlias("sell")
    SELL;
    fun isBuy(): Boolean {
        return this == BUY
    }
}

enum class OrderType {
    Market,
    Limit,
    FillOrKill,
    AllOrNothing
}

enum class MarketLifecycleOperation {
    @JsonAlias("open")
    OPEN,

    @JsonAlias("close")
    CLOSE;
}


enum class PositionType {
    LONG,
    SHORT
}

fun getPositionType(ordinal: Int): PositionType {
    return when (ordinal) {
        0 -> PositionType.LONG
        1 -> PositionType.SHORT
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

fun getTradeType(ordinal: Int): TradeType {
    return when (ordinal) {
        0 -> TradeType.BUY
        1 -> TradeType.SELL
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

typealias SortedOrderBook = MutableMap<Int, ArrayList<OrderBookEntry>>

interface OrderBook {
    val bid: Map<BigDecimal, List<Order>>
    val ask: Map<BigDecimal, List<Order>>
}

interface OrderBookRecord : OrderBook {
    val publishedTick: Long
}

data class ConcreteOrderBook(
    override val bid: Map<BigDecimal, List<Order>>,
    override val ask: Map<BigDecimal, List<Order>>
) : OrderBook

// Ticker, price, size: eventualy should move this to a dedicated class, this is asking for problems
data class OrderBookEntry(
    val id: Int,
    val user: String,
    val ticker: Ticker,
    val tradeType: TradeType,
    val size: Int,
    val price: Int,
    val orderType: OrderType,
    val receivedTick: Long,
    var finalSize: Int = 0,
)

fun getOrderType(ordinal: Int): OrderType {
    return when (ordinal) {
        0 -> OrderType.Market
        1 -> OrderType.Limit
        2 -> OrderType.FillOrKill
        3 -> OrderType.AllOrNothing
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

interface Order {
    val ticker: Ticker;
    val tradeType: TradeType;
    val orderType: OrderType;
    val size: Int;
    val email: String;
}

data class ExchangeRequestDto(
    val email: String,
    val ticker: Ticker,
)

data class PositionRecord(
    val id: Int,
    val ticker: Ticker,
    val size: Int,
)

