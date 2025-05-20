import com.iainschmitt.januaryplaygroundbackend.shared.*
import java.sql.Statement

// This isn't really a true DAO because that implies more of a 1-to-1 relationship with tables, but
// this really needed to be somewhere other than `MarketService`
class MarketDao(
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

    fun getQuote(ticker: Ticker): Quote? {
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
                        else Quote(ticker, bid, ask)
                    } else null
                }
            }
        }
    }

    fun buyMatchingOrderBook(
        ticker: Ticker
    ): List<BuyOrderBookEntry> {
        val matchingPendingOrders = ArrayList<BuyOrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement(
                """
                select o.id, o.user, o.ticker, o.trade_type, o.size, o.price, o.order_type, o.received_tick, coalesce(position_count, 0) as seller_position_count
                    from order_records o
                             left join (
                                    select user, sum(size) as position_count
                                    from position_records
                                    where position_type = 0
                                    group by user
                             ) p on p.user = o.user
                    where o.ticker = ?
                      and o.trade_type = ?
                      and o.filled_tick = -1;
                """
            )
        }.use { stmt ->
            stmt.setString(1, ticker)
            stmt.setInt(
                2, TradeType.SELL.ordinal
            )

            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    matchingPendingOrders.add(
                        BuyOrderBookEntry(
                            rs.getInt("id"),
                            rs.getString("user"),
                            rs.getString("ticker"),
                            getTradeType(rs.getInt("trade_type")),
                            rs.getInt("size"),
                            rs.getInt("price"),
                            getOrderType(rs.getInt("order_type")),
                            rs.getLong("received_tick"),
                            rs.getInt("seller_position_count")
                        )
                    )
                }
            }
        }
        return matchingPendingOrders
    }

    fun sellMatchingOrderBook(
        ticker: Ticker,
    ): List<SellOrderBookEntry> {
        val matchingPendingOrders = ArrayList<SellOrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement(
            """
                select o.id, o.user, o.ticker, o.trade_type, o.size, o.price, o.order_type, o.received_tick, u.balance AS buyer_balance
                    from order_records o join user u
                        on u.email = o.user
                    where o.ticker = ?
                        and o.trade_type = ?
                        and o.filled_tick = -1;
                """
                ).use { stmt ->
                    stmt.setString(1, ticker)
                    stmt.setInt(
                        2, TradeType.BUY.ordinal
                    )

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            matchingPendingOrders.add(
                                SellOrderBookEntry(
                                    rs.getInt("id"),
                                    rs.getString("user"),
                                    rs.getString("ticker"),
                                    getTradeType(rs.getInt("trade_type")),
                                    rs.getInt("size"),
                                    rs.getInt("price"),
                                    getOrderType(rs.getInt("order_type")),
                                    rs.getLong("received_tick"),
                                    rs.getInt("buyer_balance")
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
    ): List<IOrderBookEntry> {
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
        val filledTick: Long = System.currentTimeMillis()
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
                stmt.setLong(1, filledTick)
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
            }
            // Addressing orderer
            conn.prepareStatement("update user set balance = balance - ? where email = ?").use { stmt ->
                stmt.setInt(1, marketOrderProposal.sumOf { entry -> entry.size * entry.price } * order.sign())
                stmt.setString(2, order.email)
                stmt.executeUpdate()
            }

            // SQLite docs:
            // 'On an INSERT, if the ROWID or INTEGER PRIMARY KEY column is not explicitly given a value, then it
            //  will be filled automatically with an unused integer, usually one more than the largest ROWID currently in use.;
            return@query conn.prepareStatement(
                "insert into position_records (user, ticker, position_type, size) values (?, ?, ?, ? )",
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, order.email)
                stmt.setString(2, order.ticker)
                stmt.setInt(3, PositionType.LONG.ordinal) //TODO: think about long/short orders
                stmt.setInt(4, order.size)
                stmt.executeUpdate()

                val rs = stmt.generatedKeys
                if (rs.next()) rs.getLong(1) else -1
            }
        }
        return if (positionId != -1L) FilledOrderRecord(positionId, filledTick) else null
    }

    fun createLimitPendingOrder(order: LimitOrderRequest): LimitPendingOrderRecord? {
        var orderId: Long? = null
        val receivedTick: Long = System.currentTimeMillis()

        orderId = db.query { conn ->
            conn.prepareStatement(
                """insert into order_records (user, ticker, trade_type, size, price, order_type, filled_tick, received_tick)
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
            SELECT id, size
            FROM position_records
            WHERE user = ? AND ticker = ? AND position_type = ?
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
            conn.prepareStatement("select id, user, ticker, trade_type, size, price, order_type, received_tick from order_records where user = ? and ticker = ? and filled_tick = -1")
                .use { stmt ->
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

}
