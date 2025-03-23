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
    BUY,

    //@JsonAlias("sell")
    SELL;
    fun isBuy(): Boolean {
        return this == BUY
    }
}

// This is what made the case for extension methods to me

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
    val price: Int,
    val size: Int,
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