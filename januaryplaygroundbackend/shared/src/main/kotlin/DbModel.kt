package com.iainschmitt.januaryplaygroundbackend.shared

data class TickerRecord(
    val ticker: Ticker,
    private val openColumn: Int
) {
    val open = openColumn != 0
}

data class FilledOrderRecord(
    val positionId: Long,
    val filledTick: Long
)

data class LimitPendingOrderRecord(
    val orderId: Long,
    val receivedTick: Long
)

data class DeleteAllPositionsRecord(
    val cancelledTick: Long,
    val orderCount: Int
)

data class ValidOrderRecord<T : OrderRequest>(
    val order: T,
    val userBalance: Int
)

data class StatelessQuote(
    val ticker: Ticker,
    val bid: Int,
    val ask: Int,
) {
    fun getQuote(exchangeSequenceTimestamp: Long): Quote {
        return Quote(ticker, bid, ask, exchangeSequenceTimestamp)
    }

    override fun toString() = "$ticker: $bid/$ask"
}

data class Quote(
    val ticker: Ticker,
    val bid: Int,
    val ask: Int,
    val exchangeSequenceTimestamp: Long,
) {
    override fun toString() = "$ticker: $bid/$ask @ $exchangeSequenceTimestamp"
    fun hasBidAskEmpty() = bid == -1 && ask == -1
    fun hasbidAskFull() = bid != -1 && ask != -1
    fun hasBidsWithoutAsks() = bid != -1 && ask == -1
    fun hasAsksWithoutBids() = bid == -1 && ask != -1

    //! Cannot assume bidaskfull
    private fun spread() = ask - bid

    private fun midpoint() = (ask - bid ) / 2
    fun stronglyEquivalent(other: Quote) = ticker == other.ticker && bid == other.bid && ask == other.ask

    fun weaklyEquivalent(other: Quote) = midpoint() == other.midpoint()
}

