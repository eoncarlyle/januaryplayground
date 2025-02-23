package com.iainschmitt.januaryplaygroundbackend.shared

import com.fasterxml.jackson.annotation.JsonAlias
import java.math.BigDecimal

typealias Ticker = String

// As much as I like the Wlaschin typing, let's keep this simple for now

//class Ticker private constructor(private val symbol: String) {
//    override fun toString(): String = symbol
//
//    companion object {
//        fun factoryOf(symbols: Set<String>): TickerFactory {
//            return TickerFactory(symbols)
//        }
//    }
//
//    class TickerFactory internal constructor(private val validSymbols: Set<String>) {
//        fun create(symbol: String): Ticker? {
//            return if (symbol in validSymbols) {
//                Ticker(symbol)
//            } else {
//                null
//            }
//        }
//    }
//}

enum class TradeType {
    //@JsonAlias("buy")
    Buy,
    //@JsonAlias("sell")
    Sell;
}

// Much better than the empty interface trick in Java

/*
sealed interface OrderType {
    // Don't understand the `object` vs. class distinction here
    data object Market : OrderType
    data class Limit(final val price: BigDecimal) : OrderType

    // May need more terms for these other two
    data class FillOrKill(final val price: BigDecimal) : OrderType
    data class AllOrNothing(final val price: BigDecimal) : OrderType
}
 */

enum class OrderType {
    Market,
    Limit,
    FillOrKill,
    AllOrNothing
}

fun getOrderType(ordinal: Int): OrderType {
    return when(ordinal) {
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

interface OrderRequest : Order {
}

interface OrderCancel {
    val orderId: String;
    val email: String;
}

interface OrderCancelRequest : OrderCancel {

}

// Only needed for market and fill-or-kill
interface OrderAcknowledged : Order {
    val orderId: String
    val acknowledgedTick: Long
}

// Can have multiple with a singe
interface OrderFilled : Order {
    val orderId: String
    val filledTick: Long
}

enum class OrderFailedCode {
    MARKET_CLOSED,
    UNKNOWN_TICKER,
    UNKNOWN_TRADER,
    INSUFFICIENT_CREDITS,
    INSUFFICIENT_SHARES, // Market, FOK
}

interface OrderFailed : Order {
    val failedTick: Long
    val orderFailedCode: OrderFailedCode
}

enum class OrderCancelFailedCode {
    UNKNOWN_ORDER,
    UNKNOWN_TRADER,
    ORDER_FILLED,
    INTERNAL_ERROR
}

interface OrderCancelConfirmed : OrderCancel {
    val confirmedTick: Long
}

interface OrderCancelFailed {
    val orderId: String
    val failedTick: Long
    val orderCancelFailedCode: OrderCancelFailedCode
}

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