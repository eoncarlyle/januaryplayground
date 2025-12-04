import arrow.core.raise.option
import com.iainschmitt.januaryplaygroundbackend.shared.*
import ledger.Ledger
import ledger.LedgerK
import ledger.LedgerTableOperation
import ledger.LedgerTableOperationType
import ledger.orderLedgerOperation
import ledger.positionLedgerOperation
import ledger.userLedgerBalanceUpdate
import ledger.userLedgerOperation
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

    fun _fillOrder(
        order: Order,
        marketOrderProposal: ArrayList<OrderBookEntry>
    ) {
        val orderFilledTick: Long = System.currentTimeMillis()
        val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
        val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }


        ledger.submitWithHandle({
            it.positionRecords[LedgerK.PositionRecords(
                order.email, order.ticker, PositionType
                    .LONG
            )]
        }) { state ->

            val completeCounterpartyLedgers: List<LedgerTableOperation> = completeOrders.flatMap { orderBookEntry ->
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

            val partialCounterpartyLedgers: List<LedgerTableOperation> = partialOrders.flatMap { orderBookEntry ->
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

            //TODO: address orderer, do the buyerLongPositionUpdate/sellerLongPositionUpdate
            //TODO: this should be in a new method

            listOf()
        }
    }

    //TODO: I need to re-read this to better understand if there are any issues with limit order usages
    fun fillOrder(
        order: Order,
        marketOrderProposal: ArrayList<OrderBookEntry>
    ): FilledOrderRecord? {
        val orderFilledTick: Long = System.currentTimeMillis()
        val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
        val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }
        // From perspective of the counterparties:
        // this is the direction that the counterparty balances will go

        val positionId = db.query { conn ->
            val completeOrderIds = completeOrders.map { it.id }
            val orderIdSqlList = completeOrderIds.joinToString(prefix = "(", postfix = ")") { "?" }
            val completeOrderUpdate = "update order_records set filled_tick = ? where id in $orderIdSqlList"

            // Addressing complete orders
            conn.prepareStatement(completeOrderUpdate).use { stmt ->
                stmt.setLong(1, orderFilledTick)
                completeOrderIds.forEachIndexed { index, completeOrderId ->
                    stmt.setInt(index + 2, completeOrderId)
                }
                stmt.executeUpdate()
            }
            // TODO: There is certainly a way to do this in a single query
            for (completeOrder in completeOrders) {
                conn.prepareStatement("update user set balance = balance + ? where email = ?").use { stmt ->
                    stmt.setInt(1, completeOrder.size * completeOrder.price * order.sign())
                    stmt.setString(2, completeOrder.user)
                    stmt.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    update position_records set size = size - ?
                        where id = (
                            select id from position_records
                                where user = ?
                                and ticker = ?
                                and position_type = ?
                            order by received_tick limit 1
                        );
                    """
                ).use { stmt ->
                    stmt.setInt(1, completeOrder.size * order.sign())
                    stmt.setString(2, completeOrder.user)
                    stmt.setString(3, order.ticker)
                    stmt.setInt(4, PositionType.LONG.ordinal)

                    stmt.executeUpdate()
                }
            }

            // Addressing partial orders
            // There really only should be _one_ of these ever run
            for (partialOrder in partialOrders) {
                conn.prepareStatement("update order_records set size = ? where id = ?").use { stmt ->
                    stmt.setInt(1, partialOrder.finalSize)
                    stmt.setInt(2, partialOrder.id)
                    stmt.executeUpdate()
                }

                conn.prepareStatement("update user set balance = balance + ? where email = ?").use { stmt ->
                    stmt.setInt(1, (partialOrder.size - partialOrder.finalSize) * partialOrder.price * order.sign())
                    stmt.setString(2, partialOrder.user)
                    stmt.executeUpdate()
                }

                conn.prepareStatement(
                    """
                    update position_records set size = size - ?
                        where id = (
                            select id from position_records
                                where user = ? -- Note the unique contraint
                                and ticker = ?
                                and position_type = ?
                        );
                    """
                ).use { stmt ->
                    stmt.setInt(1, (partialOrder.size - partialOrder.finalSize) * order.sign())
                    stmt.setString(2, partialOrder.user)
                    stmt.setString(3, order.ticker)
                    stmt.setInt(4, PositionType.LONG.ordinal)

                    stmt.executeUpdate()
                }
            }
            // Addressing orderer
            conn.prepareStatement("update user set balance = balance - ? where email = ?").use { stmt ->
                stmt.setInt(
                    1,
                    marketOrderProposal.sumOf { entry -> (entry.size - entry.finalSize) * entry.price } * order.sign())
                stmt.setString(2, order.email)
                stmt.executeUpdate()
            }

            //TODO: think about long/short orders
            return@query if (order.isBuy()) buyerLongPositionUpdate(
                conn,
                order,
                orderFilledTick
            ) else sellerLongPositionUpdate(conn, order, orderFilledTick)
        }
        return if (positionId != -1L) FilledOrderRecord(positionId, orderFilledTick) else null
    }

    private fun buyerLongPositionUpdate(
        conn: Connection,
        order: Order,
        orderFilledTick: Long
    ): Long = conn.prepareStatement(
        // SQLite docs:
        // 'On an INSERT, if the ROWID or INTEGER PRIMARY KEY column is not explicitly given a value, then it
        //  will be filled automatically with an unused integer, usually one more than the largest ROWID currently in use.;
        """
                insert into position_records (user, ticker, position_type, size, received_tick) values (?, ?, ?, ?, ?)
                    on conflict (user, ticker, position_type)
                    do update set size = size + excluded.size, received_tick = excluded.received_tick
            """,
        Statement.RETURN_GENERATED_KEYS
    ).use { stmt ->
        stmt.setString(1, order.email)
        stmt.setString(2, order.ticker)
        stmt.setInt(3, PositionType.LONG.ordinal)
        stmt.setInt(4, order.size)
        stmt.setLong(5, orderFilledTick)
        stmt.executeUpdate()

        val rs = stmt.generatedKeys
        if (rs.next()) rs.getLong(1) else -1
    }

    private fun sellerLongPositionUpdate(
        conn: Connection,
        order: Order,
        orderFilledTick: Long
    ): Long = conn.prepareStatement(
        """
            update position_records set 
                size = size - ?, 
                received_tick = ?
            where id = (
                select id from position_records
                where user = ?
                and ticker = ?
                and position_type = ?
                order by received_tick limit 1
            )
            returning id, size;
        """
    ).use { stmt ->
        stmt.setInt(1, order.size)
        stmt.setLong(2, orderFilledTick)
        stmt.setString(3, order.email)
        stmt.setString(4, order.ticker)
        stmt.setInt(5, PositionType.LONG.ordinal)

        val rs = stmt.executeQuery()
        if (rs.next()) {
            if (rs.getInt(2) == 0) {
                deleteEmptyPositions(conn, order.email, order.ticker)
            }
            rs.getLong(1)
        } else {
            -1
        }
    }

    private fun deleteEmptyPositions(
        conn: Connection,
        user: String,
        ticker: Ticker,
    ) {
        conn.prepareStatement(
            """
            delete from position_records 
                where user = ? and ticker = ? and position_type = ? and size = 0;
            """
        ).use { stmt ->
            stmt.setString(1, user)
            stmt.setString(2, ticker)
            stmt.setInt(3, PositionType.LONG.ordinal)
            stmt.executeUpdate()
        }
    }

    // TODO more resilient handling of errors
    private fun deleteFilledOrders(
        conn: Connection,
        ticker: Ticker,
    ) {
        conn.prepareStatement(
            """
            delete from main.order_records
                where ticker = ? and filled_tick != -1;
            """
        ).use { stmt ->
            stmt.setString(1, ticker)
            stmt.setInt(2, PositionType.LONG.ordinal)
            stmt.executeUpdate()
        }
    }

    private fun statePair(conn: Connection): Pair<Int, Int> = conn.prepareStatement(
        """
                select positions, balances
                    from (
                        select
                            (select sum(size) from position_records) as positions,
                            (select sum(balance) from user) as balances
            )
            """
    ).use { stmt ->
        stmt.executeQuery().use { rs ->
            Pair(rs.getInt("positions"), rs.getInt("balances"))
        }
    }

    fun createLimitPendingOrder(order: LimitOrderRequest): LimitPendingOrderRecord? {
        var orderId: Long? = null
        val receivedTick: Long = System.currentTimeMillis()

        orderId = db.query { conn ->
            conn.prepareStatement(
                """
                insert into order_records (user, ticker, trade_type, size, price, order_type, filled_tick, received_tick)
                    values (?, ?, ?, ?, ?, ?, ?, ?) 
                """
            ).use { stmt ->
                stmt.setString(1, order.email)
                stmt.setString(2, order.ticker)
                stmt.setInt(3, order.tradeType.ordinal)
                stmt.setInt(4, order.size)
                stmt.setInt(5, order.price)
                stmt.setInt(6, order.orderType.ordinal)
                stmt.setLong(7, -1L)
                stmt.setLong(8, receivedTick)
                stmt.executeUpdate()

                val rs = stmt.generatedKeys
                return@query if (rs.next()) rs.getLong(1) else -1
            }
        }
        return if (orderId != -1L) LimitPendingOrderRecord(orderId, receivedTick) else null
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
