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

data class ValidOrderRecord<T: OrderRequest>(
    val order: T,
    val userBalance: Int
)

data class Quote(
    val ticker: Ticker,
    val bid: Int,
    val ask: Int
)
