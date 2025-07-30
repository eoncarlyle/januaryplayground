package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.Option
import arrow.core.some
import arrow.core.none

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
    fun spread() = ask - bid

    private fun midpoint() = (ask - bid ) / 2
    fun stronglyEquivalent(other: Quote) = ticker == other.ticker && bid == other.bid && ask == other.ask

    fun weaklyEquivalent(other: Quote) = midpoint() == other.midpoint()
}

enum class NotificationCategory {
    CREDIT_BALANCE;
}
enum class NotificationOperation {
    LESS_THAN,
    GREATER_THAN
}

data class NotificationRule(
    val user: String,
    val category: NotificationCategory,
    val operation: NotificationOperation,
    val timestamp: Long,
    val dimension: Int
)
fun getNotificationCategory(ordinal: Int): Option<NotificationCategory> {
    return when (ordinal) {
        0 -> NotificationCategory.CREDIT_BALANCE.some();
        else -> none();
    }
}

fun getNotificationOperation(ordinal: Int): Option<NotificationOperation> {
    return when (ordinal) {
        0 -> NotificationOperation.LESS_THAN.some();
        1 -> NotificationOperation.GREATER_THAN.some();
        else -> none();
    }
}

