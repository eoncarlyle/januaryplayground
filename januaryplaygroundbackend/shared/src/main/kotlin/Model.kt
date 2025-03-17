package com.iainschmitt.januaryplaygroundbackend.shared

import com.fasterxml.jackson.annotation.JsonAlias
import java.math.BigDecimal
import arrow.core.*

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
    fun isBuy(): Boolean {
        return this == Buy
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

fun Order.sign(): Int {
    return if (this.tradeType == TradeType.Buy) 1 else -1
}

interface OrderRequest : Order {
}

interface OrderCancel {
    val orderId: Int
    val email: String
}

interface OrderCancelRequest : OrderCancel {

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
) : IOrderAcknowledged, LimitOrderRespponse

// Can have multiple with a singe
interface IOrderFilled : Order {
    val positionId: Long
    val filledTime: Long
}

interface MarketOrderResponse {

}

interface LimitOrderRespponse {

}

data class OrderFilled(
    override val ticker: Ticker,
    override val positionId: Long,
    override val filledTime: Long,
    override val tradeType: TradeType,
    override val orderType: OrderType,
    override val size: Int,
    override val email: String
) : IOrderFilled, MarketOrderResponse, LimitOrderRespponse

interface IOrderPartialFilled : Order {
    val orderId: Int
    val filledTick: Long
    val finalSize: Long // The size in `Order` corresponds to the transacted size
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

// Should really split out incoming, outgoing order types
typealias OrderFailure = Pair<OrderFailureCode, String>
typealias OrderResult<T> = Either<OrderFailure, T>

interface OrderFailed : Order {
    val failedTick: Long
    val orderFailedCode: OrderFailureCode
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
    val orderId: Int
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

fun getPositionType(ordinal: Int): PositionType {
    return when (ordinal) {
        0 -> PositionType.LONG
        1 -> PositionType.SHORT
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

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
typealias SortedOrderBook = MutableMap<Int, ArrayList<OrderBookEntry>>