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
                    requestingUserLedgers(marketOrderProposal, completeOrders, orderFilledTick, state, order)

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
            counterparty.balance + (orderBookEntry.size - orderBookEntry.finalSize) * orderBookEntry.price * order
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
            )) { ledgerState -> ledgerState.orderRecords.keys.maxBy { it.id }  }
        }
    }

    fun getUserLongPositions(userEmail: String, ticker: Ticker): List<PositionRecord> {
        return getUserPositions(userEmail, ticker, PositionType.LONG)
    }

    fun getUserShortPositions(userEmail: String, ticker: Ticker): List<PositionRecord> {
        return getUserPositions(userEmail, ticker, PositionType.SHORT)
    }

    fun getUserPositions(userEmail: String, ticker: Ticker, positionType: PositionType): List<PositionRecord> =
        db.query { conn ->
            conn.prepareStatement(
                """
                select id, size from position_records
                    where user = ? AND ticker = ? AND position_type = ?
                """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setString(2, ticker)
                stmt.setInt(3, positionType.ordinal)

                val rs = stmt.executeQuery()
                val positions = mutableListOf<PositionRecord>()

                while (rs.next()) {
                    positions.add(
                        PositionRecord(
                            id = rs.getInt("id"),
                            ticker = ticker,
                            positionType = positionType,
                            size = rs.getInt("size")
                        )
                    )
                }
                positions
            }
        }

    fun getUserOrders(userEmail: String, ticker: Ticker): List<OrderBookEntry> {
        val matchingPendingOrders = ArrayList<OrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement(
                """
                select id, user, ticker, trade_type, size, price, order_type, received_tick from order_records
                    where user = ? and ticker = ? and filled_tick = -1
                """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setString(2, ticker)
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        matchingPendingOrders.add(
                            OrderBookEntry(
                                rs.getInt("id"),
                                rs.getString("user"),
                                rs.getString("ticker"),
                                getTradeType(rs.getInt("trade_type")),
                                rs.getInt("size"),
                                rs.getInt("price"),
                                getOrderType(rs.getInt("order_type")),
                                rs.getLong("received_tick")
                            )
                        )
                    }
                }
            }
        }
        return matchingPendingOrders
    }

    fun getState(): Pair<Int, Int> = db.query { conn ->
        conn.prepareStatement(
            """
               select
                   (select sum(size) from position_records) as position_sum,
                   (select sum(balance) from user) as credit_sum
               """
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) {
                    Pair(
                        rs.getInt("position_sum"),
                        rs.getInt("credit_sum")
                    )
                } else {
                    Pair(-1, -1)
                }
            }
        }
    }

    fun deleteAllUserOrders(userEmail: String, ticker: Ticker): DeleteAllPositionsRecord {
        val cancelledTick: Long = System.currentTimeMillis()
        val orderCount = db.query { conn ->
            conn.prepareStatement("delete from order_records where user = ? and ticker = ? and filled_tick = -1")
                .use { stmt ->
                    stmt.setString(1, userEmail)
                    stmt.setString(2, ticker)
                    stmt.executeUpdate()
                }
        }
        return DeleteAllPositionsRecord(cancelledTick, orderCount)
    }

    fun userAudit(): List<Pair<String, Int>> {
        val results = ArrayList<Pair<String, Int>>()
        db.query { conn ->
            conn.prepareStatement("select email, balance from user").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        results.add(Pair(rs.getString("email"), rs.getInt("balance")))
                    }
                }
            }
        }
        return results
    }

    fun getNotificationRules(): MutableSet<NotificationRule> {
        val rules = HashSet<NotificationRule>()
        db.query { conn ->
            conn.prepareStatement("select user, category, operation, timestamp, dimension from notification_rules")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            val categoryOrdinal = rs.getInt("category")
                            val operationOrdinal = rs.getInt("operation")

                            option {
                                val category = getNotificationCategory(categoryOrdinal).bind()
                                val operation = getNotificationOperation(operationOrdinal).bind()
                                rules.add(
                                    NotificationRule(
                                        rs.getString("user"),
                                        category,
                                        operation,
                                        rs.getLong("timestamp"),
                                        rs.getInt("dimension")
                                    )
                                )
                            }
                        }
                    }
                }
        }
        return rules
    }

    fun createNotificationRule(rule: NotificationRule) {
        val (userEmail, category, operation, timestamp, dimension) = rule

        db.query { conn ->
            conn.prepareStatement(
                """
                insert or replace into notification_rules (user, category, operation, timestamp, dimension)
                    values(?, ?, ?, ?, ?)
                """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setInt(2, category.ordinal)
                stmt.setInt(3, operation.ordinal)
                stmt.setLong(4, timestamp)
                stmt.setInt(5, dimension)

                stmt.executeUpdate()
            }
        }
    }

    fun deleteNotificationRule(rule: NotificationRule) {
        val (userEmail, category, operation, timestamp, dimension) = rule //Kotlin talk: talk about destructuring

        db.query { conn ->
            conn.prepareStatement(
                """
                delete from notification_rules 
                    where user = ? and category = ? and operation = ? and timestamp = ? and dimension = ?
                """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setInt(2, category.ordinal)
                stmt.setInt(3, operation.ordinal)
                stmt.setLong(4, timestamp)
                stmt.setInt(5, dimension)
            }
        }
    }
}
