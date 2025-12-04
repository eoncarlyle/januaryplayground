import arrow.core.raise.option
import arrow.core.Either
import com.iainschmitt.januaryplaygroundbackend.shared.*
import ledger.*
import java.sql.Connection
import java.sql.Statement
import kotlin.collections.filter
import kotlin.collections.listOf

// This isn't really a true DAO because that implies more of a 1-to-1 relationship with tables, but
// this really needed to be somewhere other than `MarketService`
class StreamingExchangeDao(
    private val db: DatabaseHelper,
    private val ledger: Ledger
) {


    fun getUserBalance(userEmail: String): Int? = ledger.getLedgerState().users[LedgerK.Users(userEmail)]?.balance


    fun getTicker(ticker: Ticker): TickerRecord? = ledger.getLedgerState().tickers[LedgerK.Tickers(ticker)].let {
        if (it != null) {
            TickerRecord(ticker, if (it.open) 1 else 0)
        } else null
    }

    fun getAllTickers(): List<TickerRecord> = ledger.getLedgerState().tickers.map {
        TickerRecord(
            it.key.symbol,
            if (it.value.open) 1 else 0
        )
    }

    fun unfilledOrderExists(pendingOrderId: Int, email: String): Boolean = ledger.getLedgerState()
        .orderRecords[LedgerK.OrderRecords(pendingOrderId.toLong())].let {
        if (it == null) false else (it.userEmail
                == email) && it.filledTick == -1L
    }

    fun getStatelessQuote(ticker: Ticker) = ledger.getWithHandle {
        val bid = ledger.getLedgerState().orderRecords.filter {
            it.value.ticker == ticker && it.value.tradeType ==
                    TradeType.SELL && it.value.filledTick == -1L
        }.map { it.value.price }.maxByOrNull { it } ?: -1

        val ask = ledger.getLedgerState().orderRecords.filter {
            it.value.ticker == ticker && it.value.tradeType ==
                    TradeType.BUY && it.value.filledTick == -1L
        }.map { it.value.price }.minByOrNull { it } ?: -1

        StatelessQuote(ticker, bid, ask)
    }

    fun getAllStatelessQuotes(): List<StatelessQuote> = ledger.getLedgerState().tickers.map { ticker ->
        val bid = ledger.getLedgerState().orderRecords.filter {
            it.value.ticker == ticker.key.symbol && it.value.tradeType ==
                    TradeType.SELL && it.value.filledTick == -1L
        }.map { it.value.price }.maxByOrNull { it } ?: -1

        val ask = ledger.getLedgerState().orderRecords.filter {
            it.value.ticker == ticker.key.symbol && it.value.tradeType ==
                    TradeType.BUY && it.value.filledTick == -1L
        }.map { it.value.price }.minByOrNull { it } ?: -1
        StatelessQuote(ticker.key.symbol, bid, ask)
    }

    private fun buyMatchingOrderBook(
        ticker: Ticker
    ) = ledger.getWithHandle { state ->
        state.orderRecords.map { order ->
            val initialConditions = order.value.ticker == ticker && order.value.tradeType == TradeType.SELL && order
                .value
                .filledTick == -1L

            if (!initialConditions) {
                return@map null
            } else {
                val sellerPositionCount = state.positionRecords.filter { position ->
                    position.key.userEmail == order.value.userEmail && position.key.ticker == order.value.ticker
                }.map { it.value.size }.sum()

                return@map if (sellerPositionCount >= order.value.size) {
                    OrderBookEntry(
                        order.key.id.toInt(),
                        order.value.userEmail,
                        order.value.ticker,
                        order.value.tradeType,
                        order.value.size,
                        order.value.price,
                        order.value.orderType,
                        order.value.receivedTick,
                        sellerPositionCount
                    )
                } else null
            }
        }.filterNotNull()
    }

    private fun sellMatchingOrderBook(
        ticker: Ticker,
    ) = ledger.getWithHandle { state ->
        state.orderRecords.map { order ->
            val initialConditions =
                order.value.ticker == ticker && order.value.tradeType == TradeType.BUY && order.value
                    .filledTick == -1L

            if (!initialConditions) {
                return@map null
            } else {
                val buyerBalance = state.users.size

                return@map if (buyerBalance >= order.value.size * order.value.price) {
                    OrderBookEntry(
                        order.key.id.toInt(),
                        order.value.userEmail,
                        order.value.ticker,
                        order.value.tradeType,
                        order.value.size,
                        order.value.price,
                        order.value.orderType,
                        order.value.receivedTick,
                    )
                } else null
            }
        }
    }.filterNotNull()


    fun getMatchingOrderBook(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): List<OrderBookEntry> = if (pendingOrderTradeType.isBuy()) {
        buyMatchingOrderBook(ticker)
    } else {
        sellMatchingOrderBook(ticker)
    }

    fun orderLedgerOperation(
        operation: LedgerTableOperationType,
        orderBookEntry: OrderBookEntry,
        orderFilledTick: Long
    ) =
        orderLedgerOperation(
            LedgerTableOperationType.Update,
            orderBookEntry.id,
            orderBookEntry.user,
            orderBookEntry.ticker,
            orderBookEntry.tradeType,
            orderBookEntry.size,
            orderBookEntry.price,
            orderBookEntry.orderType,
            orderFilledTick,
            orderBookEntry.receivedTick
        )

    fun fillOrder(
        order: Order,
        marketOrderProposal: ArrayList<OrderBookEntry>
    ): Either<Throwable, Unit> {
        val orderFilledTick: Long = System.currentTimeMillis()
        val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
        val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }

        return Either.catch {
            ledger.submitWithHandle({
            }) { state ->
                val completeCounterpartyLedgers =
                    completeCounterpartyLedgers(completeOrders, orderFilledTick, state, order)

                val partialCounterpartyLedgers: List<LedgerTableOperation> =
                    partialCounterpartyLedgers(partialOrders, orderFilledTick, state, order)

                val requestingUserLedgers =
                    requestingUserLedgers(marketOrderProposal, state, order)

                listOf(completeCounterpartyLedgers, partialCounterpartyLedgers, requestingUserLedgers).flatten()
            }.get()
        }
    }

    private fun partialCounterpartyLedgers(
        partialOrders: List<OrderBookEntry>,
        orderFilledTick: Long,
        state: LedgerState,
        order: Order
    ) = partialOrders.flatMap { orderBookEntry ->
        val orderOperation = orderLedgerOperation(
            LedgerTableOperationType.Update,
            orderBookEntry,
            orderFilledTick,
        )

        val counterparty = state.users[LedgerK.Users(orderBookEntry.user)]
        assert(counterparty != null)

        val userOperation = userLedgerBalanceUpdate(
            orderBookEntry.user,
            counterparty!!,
            counterparty.balance + (orderBookEntry.size - orderBookEntry.finalSize) * orderBookEntry.price * order.sign()
        )

        val position = state.positionRecords[LedgerK.PositionRecords(
            orderBookEntry.user, orderBookEntry
                .ticker, PositionType.LONG
        )]
        assert(position != null)

        val positionOperation =
            positionLedgerOperation(
                LedgerTableOperationType.Create,
                orderBookEntry.user,
                orderBookEntry.ticker,
                PositionType.LONG,
                position!!.size - orderBookEntry.size * order.sign(),
                position.receivedTick
            )

        listOf(orderOperation, userOperation, positionOperation)
    }

    private fun completeCounterpartyLedgers(
        completeOrders: List<OrderBookEntry>,
        orderFilledTick: Long,
        state: LedgerState,
        order: Order
    ) = completeOrders.flatMap { orderBookEntry ->
        val orderOperation = orderLedgerOperation(
            LedgerTableOperationType.Update,
            orderBookEntry,
            orderFilledTick,
        )

        val counterparty = state.users[LedgerK.Users(orderBookEntry.user)]
        assert(counterparty != null)
        // There isn't a good way to update just one field
        val userOperation = userLedgerBalanceUpdate(
            orderBookEntry.user, counterparty!!,
            counterparty.balance +
                    orderBookEntry.size * orderBookEntry.price * order.sign(),
        )

        val position = state.positionRecords[LedgerK.PositionRecords(
            orderBookEntry.user, orderBookEntry
                .ticker, PositionType.LONG
        )]
        assert(position != null)

        val positionOperation =
            positionLedgerOperation(
                LedgerTableOperationType.Create,
                orderBookEntry.user,
                orderBookEntry.ticker,
                PositionType.LONG,
                position!!.size - orderBookEntry.size * order.sign(),
                position.receivedTick
            )

        listOf(orderOperation, userOperation, positionOperation)
    }

    private fun requestingUserLedgers(
        marketOrderProposal: ArrayList<OrderBookEntry>,
        state: LedgerState,
        order: Order
    ): List<LedgerTableOperation> {
        val requestingUser = state.users[LedgerK.Users(order.email)]
        assert(requestingUser != null)
        val userOperation = userLedgerBalanceUpdate(
            order.email,
            requestingUser!!,
            requestingUser.balance - (marketOrderProposal.sumOf { entry -> (entry.size - entry.finalSize) * entry.price } * order.sign())
        )

        val existingPosition =
            state.positionRecords[LedgerK.PositionRecords(order.email, order.ticker, PositionType.LONG)]
        assert(existingPosition != null)

        val positionOperation = if (order.isBuy()) {
            positionLedgerOperation(
                LedgerTableOperationType.Update,
                order.email,
                order.ticker,
                PositionType.LONG,
                order.size,
                existingPosition!!.receivedTick
            )
        } else {
            if (existingPosition!!.size == order.size) {
                positionLedgerOperation(
                    LedgerTableOperationType.Delete,
                    order.email,
                    order.ticker,
                    PositionType.LONG,
                    order.size,
                    existingPosition.receivedTick
                )
            } else if (existingPosition.size > order.size) {
                positionLedgerOperation(
                    LedgerTableOperationType.Update,
                    order.email,
                    order.ticker,
                    PositionType.LONG,
                    order.size,
                    existingPosition.receivedTick
                )
            } else {
                throw IllegalStateException("Illegal order")
            }

        }
        return listOf(userOperation, positionOperation)
    }

    private fun deleteFilledOrders(
        conn: Connection,
        ticker: Ticker,
    ) {
        ledger.submitWithHandle({}) { ledgerState ->
            val toDelete = ledgerState.orderRecords.filter { it.value.ticker == ticker && it.value.filledTick != -1L }
            toDelete.map {
                orderLedgerOperation(
                    LedgerTableOperationType.Delete,
                    it.key.id.toInt(),
                    it.value.userEmail,
                    it.value.ticker,
                    it.value.tradeType,
                    it.value.size,
                    it.value.price,
                    it.value.orderType,
                    it.value.filledTick,
                    it.value.receivedTick
                )
            }
        }.get()
    }

    private fun statePair() = ledger.getLedgerState().let { state ->
        state.positionRecords.values.sumOf { it.size } to state.users.values.sumOf { it.balance }
    }

    fun createLimitPendingOrder(order: LimitOrderRequest) {
        ledger.submit(
            listOf(
                orderLedgerOperation(
                    LedgerTableOperationType.Delete,
                    -1,
                    order.email,
                    order.ticker,
                    order.tradeType,
                    order.size,
                    order.price,
                    order.orderType,
                    -1,
                    System.currentTimeMillis()
                )
            )
        ) { ledgerState -> ledgerState.orderRecords.keys.maxBy { it.id } }
    }

    fun getUserLongPositions(userEmail: String, ticker: Ticker): List<PositionRecord> {
        return getUserPositions(userEmail, ticker, PositionType.LONG)
    }

    fun getUserShortPositions(userEmail: String, ticker: Ticker): List<PositionRecord> {
        return getUserPositions(userEmail, ticker, PositionType.SHORT)
    }

    fun getUserPositions(userEmail: String, ticker: Ticker, positionType: PositionType): List<PositionRecord> {
        return ledger.getLedgerState().positionRecords.filter { entry ->
            entry.key.userEmail == userEmail && entry.key
                .ticker == ticker && entry.key.positionType == positionType
        }.map { entry ->
            PositionRecord(-1, entry.key.userEmail, entry.key.positionType, entry.value.size)
        } //! There aren't any position records anymore
    }

    fun getUserOrders(userEmail: String, ticker: Ticker): List<OrderBookEntry> {
        return ledger.getLedgerState().orderRecords.filter {
            it.value.userEmail == userEmail && it.value.ticker ==
                    ticker && it.value.filledTick == -1L
        }.map {
            OrderBookEntry(
                it.key.id.toInt(),
                it.value.userEmail,
                it.value.ticker,
                it.value.tradeType,
                it.value.size,
                it.value.price,
                it.value.orderType,
                it.value.receivedTick,
            )
        }
    }

    fun deleteAllUserOrders(userEmail: String, ticker: Ticker): DeleteAllPositionsRecord {
        val cancelledTick: Long = System.currentTimeMillis()
        //Talk about this monstrocity in the talk
        val orderCount = ledger.submitWithHandleResultFromInitialState({ state ->
            state.orderRecords.filter {
                it.value.userEmail ==
                        userEmail && it.value.ticker == ticker
            }.size
        }) { state ->
            state.orderRecords.filter {
                it.value.userEmail ==
                        userEmail && it.value.ticker == ticker
            }.map { entry ->
                orderLedgerOperation(
                    LedgerTableOperationType.Delete, entry.key.id.toInt(),
                    entry.value.userEmail, entry.value.ticker, entry.value.tradeType, entry.value.size, entry.value
                        .price, entry.value.orderType, entry.value.filledTick, entry.value.receivedTick
                )
            }
        }

        return DeleteAllPositionsRecord(cancelledTick, orderCount)
    }

    fun userAudit() = ledger.getLedgerState().users.map { it.key.email to it.value.balance }

    fun getNotificationRules() = ledger.getLedgerState().notificationRules.map {
        NotificationRule(
            it.key.userEmail,
            it.value.category, it.value.operation, it.value.timestamp, it.value.dimension
        )
    }.toSet()

    fun createNotificationRule(rule: NotificationRule) = ledger.submit(
        listOf(
            notificationLedgerOperation(
                LedgerTableOperationType.Update,
                rule.user,
                rule.operation,
                rule.category,
                rule.timestamp,
                rule.dimension,
            )
        )
    )

    fun deleteNotificationRule(rule: NotificationRule) = listOf(
        notificationLedgerOperation(
            LedgerTableOperationType.Delete,
            rule.user,
            rule.operation,
            rule.category,
            rule.timestamp,
            rule.dimension,
        )
    )
}
