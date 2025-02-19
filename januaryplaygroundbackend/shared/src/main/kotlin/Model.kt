package com.iainschmitt.januaryplaygroundbackend.shared

import com.fasterxml.jackson.annotation.JsonAlias
import java.math.BigDecimal

class Ticker private constructor(private val symbol: String){
    override fun toString(): String = symbol

    companion object {
        fun factoryOf(symbols: Set<String>): TickerFactory {
            return TickerFactory(symbols)
        }
    }

    class TickerFactory internal constructor(private val validSymbols: Set<String>) {
        fun create(symbol: String): Ticker? {
            return if (symbol in validSymbols) {
                Ticker(symbol)
            } else {
                null
            }
        }
    }
}

enum class TradeType {
    @JsonAlias("buy")
    BUY,
    @JsonAlias("")
    SELL;
}

// Much better than the empty interface trick in Java
sealed interface OrderType {
    // Don't understand the `object` vs. class distinction here
    data object Market : OrderType
    data class Limit(final val price: BigDecimal): OrderType
    // May need more terms for these other two
    data class FillOrKill(final val price: BigDecimal): OrderType
    data class AllOrNothing(final val price: BigDecimal): OrderType
}

interface Order {
    val tradeType: TradeType;
    val size: Int;
    val trader: String;
}

class OrderRequest(
    val ticker: Ticker,
    override val tradeType: TradeType,
    override val size: Int,
    override val trader: String,
): Order


// Only needed for market and fill-or-kill
class OrderAcknowledged(
    val ticker: Ticker,
    override val tradeType: TradeType,
    override val size: Int,
    override val trader: String,
    val id: String,
    val acknowledgedTime: Long
): Order

// Can have multiple with a singe
class OrderFilled(
    val ticker: Ticker,
    override val tradeType: TradeType,
    override val size: Int,
    override val trader: String,
    val id: String,
    val filledTime: Long
): Order

class OrderFailed(
    val ticker: Ticker,
    override val tradeType: TradeType,
    override val size: Int,
    override val trader: String,
    val failedTime: Long,
    val id: String,
    val code: Int
): Order

class OrderBook(
    val bid: Map<BigDecimal, List<Order>>,
    val ask: Map<BigDecimal, List<Order>>
)
