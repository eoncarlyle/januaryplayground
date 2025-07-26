import arrow.core.raise.option
import com.iainschmitt.januaryplaygroundbackend.shared.*
import java.sql.Connection
import java.sql.Statement

// This isn't really a true DAO because that implies more of a 1-to-1 relationship with tables, but
// this really needed to be somewhere other than `MarketService`
class ExchangeDao(
    private val db: DatabaseHelper
) {

    fun getUserBalance(userEmail: String): Int? {
        return db.query { conn ->
            conn.prepareStatement("select balance from user where email = ?").use { stmt ->
                stmt.setString(1, userEmail)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("balance") else null }
            }
        }
    }

    fun getTicker(ticker: Ticker): TickerRecord? {
        return db.query { conn ->
            conn.prepareStatement("select symbol, open from ticker where symbol = ?").use { stmt ->
                stmt.setString(1, ticker)
                stmt.executeQuery().use { rs -> if (rs.next()) TickerRecord(rs.getString(1), rs.getInt(2)) else null }
            }
        }
    }

    fun getAllTickers(): ArrayList<TickerRecord> {
        val tickers = ArrayList<TickerRecord>()
        db.query { conn ->
            conn.prepareStatement("select symbol, open from ticker").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tickers.add(TickerRecord(rs.getString("symbol"), rs.getInt("open")))
                    }
                }
            }
        }
        return tickers
    }

    fun unfilledOrderExists(pendingOrderId: Int, email: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select id from order_records where id = ? and user = ? and filled_tick = -1")
                .use { stmt ->
                    stmt.setInt(1, pendingOrderId)
                    stmt.setString(2, email)
                    stmt.executeQuery().use { rs -> rs.next() }
                }
        }
    }

    fun getPartialQuote(ticker: Ticker): StatelessQuote? {
        return db.query { conn ->
            conn.prepareStatement(
                """
             select
                coalesce((select max(price) from order_records
                where ticker = ? and trade_type = 0 and filled_tick = -1), -1) as bid,
                coalesce((select min(price) from order_records
                where ticker = ? and trade_type = 1 and filled_tick = -1), -1) as ask;
             """
            ).use { stmt ->
                stmt.setString(1, ticker)
                stmt.setString(2, ticker)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) {
                        val bid = rs.getInt(1)
                        val ask = rs.getInt(2)
                        if (rs.wasNull() || ask == 0 && rs.wasNull()) null
                        else StatelessQuote(ticker, bid, ask)
                    } else null
                }
            }
        }
    }

    private fun buyMatchingOrderBook(
        ticker: Ticker
    ): List<OrderBookEntry> {
        val matchingPendingOrders = ArrayList<OrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement(
                """
                select o.id, o.user, o.ticker, o.trade_type, o.size, o.price, o.order_type, o.received_tick, seller_position_count
                    from order_records o
                             left join (
                                    select user, coalesce(sum(size), 0) as seller_position_count
                                    from position_records
                                    where position_type = ?
                                    group by user
                             ) p on p.user = o.user
                    where o.ticker = ?
                      and o.trade_type = ?
                      and o.filled_tick = -1
                      and p.seller_position_count >= o.size
                    order by o.received_tick;
                """
            ).use { stmt ->
                stmt.setInt(1, PositionType.LONG.ordinal)
                stmt.setString(2, ticker)
                stmt.setInt(
                    3, TradeType.SELL.ordinal
                )

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
                                rs.getLong("received_tick"),
                            )
                        )
                    }
                }
            }
        }
        return matchingPendingOrders
    }

    private fun sellMatchingOrderBook(
        ticker: Ticker,
    ): List<OrderBookEntry> {
        val matchingPendingOrders = ArrayList<OrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement(
                """
                select o.id, o.user, o.ticker, o.trade_type, o.size, o.price, o.order_type, o.received_tick, u.balance AS buyer_balance
                    from order_records o join user u
                        on u.email = o.user
                    where o.ticker = ?
                        and o.trade_type = ?
                        and o.filled_tick = -1
                        and u.balance >= o.price * o.size
                    order by o.received_tick;
                """
            ).use { stmt ->
                stmt.setString(1, ticker)
                stmt.setInt(
                    2, TradeType.BUY.ordinal
                )

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
                                rs.getLong("received_tick"),
                            )
                        )
                    }
                }
            }
        }
        return matchingPendingOrders
    }

    fun getMatchingOrderBook(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): List<OrderBookEntry> {
        return if (pendingOrderTradeType.isBuy()) {
            buyMatchingOrderBook(ticker)
        } else {
            sellMatchingOrderBook(ticker)
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
                    stmt.setInt(1, partialOrder.size * partialOrder.price * order.sign())
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
                stmt.setInt(1, marketOrderProposal.sumOf { entry -> entry.size * entry.price } * order.sign())
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
    ): Long {
        // SQLite docs:
        // 'On an INSERT, if the ROWID or INTEGER PRIMARY KEY column is not explicitly given a value, then it
        //  will be filled automatically with an unused integer, usually one more than the largest ROWID currently in use.;
        return conn.prepareStatement(
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
    }

    private fun sellerLongPositionUpdate(
        conn: Connection,
        order: Order,
        orderFilledTick: Long
    ): Long {
        return conn.prepareStatement(
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

    private fun statePair(conn: Connection): Pair<Int, Int> {
        return conn.prepareStatement(
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

    fun getUserPositions(userEmail: String, ticker: Ticker, positionType: PositionType): List<PositionRecord> {
        return db.query { conn ->
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

    fun getState(): Pair<Int, Int> {
        return db.query { conn ->
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

    fun getNotificationRules(): MutableSet<NotificationRule> {
        val rules = HashSet<NotificationRule>()
        db.query { conn ->
            conn.prepareStatement("select user, category, operation, dimension from notification_rules").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        val categoryOrdinal = rs.getInt("category")
                        val operationOrdinal = rs.getInt("operation")

                        option {
                            val category = getNotificationCategory(categoryOrdinal).bind()
                            val operation = getNotificationOperation(operationOrdinal).bind()
                            rules.add(
                                NotificationRule(
                                    user = rs.getString("user"),
                                    category = category,
                                    operation = operation,
                                    dimension = rs.getInt("dimension")
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
        val (userEmail, category, operation, dimension) = rule

        db.query { conn ->
            conn.prepareStatement(
                """
                INSERT OR IGNORE INTO notification_rules (user, category, operation, dimension)
                    values(?, ?, ?, ?)
                """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setInt(2, category.ordinal)
                stmt.setInt(3, operation.ordinal)
                stmt.setInt(4, dimension)

                stmt.executeUpdate()
            }
        }
    }

    fun deleteNotificationRule(rule: NotificationRule) {
        val (userEmail, category, operation, dimension) = rule

        db.query { conn ->
            conn.prepareStatement(
        """
            delete from notification_rules 
            where user = ? and category = ? and operation = ? and dimension = ?
            """
            ).use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setInt(2, category.ordinal)
                stmt.setInt(3, operation.ordinal)
                stmt.setInt(4, dimension)
            }
        }
    }

}
