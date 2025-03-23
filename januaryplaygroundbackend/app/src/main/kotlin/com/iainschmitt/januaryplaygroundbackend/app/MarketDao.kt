package com.iainschmitt.januaryplaygroundbackend.app

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

    fun getUserLongPositions(userEmail: String, ticker: Ticker): ArrayList<Int> {
        val positions = ArrayList<Int>()
        db.query { conn ->
            conn.prepareStatement("select size from position where email = ? and ticker = ? and type = ?").use { stmt ->
                stmt.setString(1, userEmail)
                stmt.setString(2, ticker)
                stmt.setInt(3, PositionType.LONG.ordinal)
                stmt.executeQuery().use { rs -> while (rs.next()) positions.add(rs.getInt("size")) }
            }
        }
        return positions
    }

    fun getTicker(ticker: Ticker): Pair<String, Int>? {
        return db.query { conn ->
            conn.prepareStatement("select symbol, open from ticker where symbol = ?").use { stmt ->
                stmt.setString(1, ticker)
                stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getInt(2)) else null }
            }
        }
    }

    fun pendingOrderExists(pendingOrderId: Int, email: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select id from pending_order where id = ? and user = ? and filled_tick != -1")
                .use { stmt ->
                    stmt.setInt(1, pendingOrderId)
                    stmt.setString(2, email)
                    stmt.executeQuery().use { rs -> rs.next() }
                }
        }
    }

    fun getMatchingOrderBook(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): ArrayList<OrderBookEntry> {
        val matchingPendingOrders = ArrayList<OrderBookEntry>()
        db.query { conn ->
            conn.prepareStatement("select id, user, ticker, price, size, order_type, received_tick from pending_order where ticker = ? and trade_type = ? and filled_tick != -1")
                .use { stmt ->
                    stmt.setString(1, ticker)
                    stmt.setInt(
                        2, pendingOrderTradeType.ordinal
                    )

                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            matchingPendingOrders.add(
                                OrderBookEntry(
                                    rs.getInt("id"),
                                    rs.getString("user"),
                                    rs.getString("ticker"),
                                    rs.getInt("price"),
                                    rs.getInt("size"),
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

    //TODO: I need to re-read this to better understand if there are any issues with limit order usages
    //TODO: Some async method for notifying limit orders
    fun fillOrder(
        order: Order,
        marketOrderProposal: ArrayList<OrderBookEntry>
    ): Pair<Long?, Long> {
        var positionId: Long? = null
        val filledTick: Long = System.currentTimeMillis()
        val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
        val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }
        // From perspective of the counterparties:
        // this is the direction that the counterparty balances will go
        db.query { conn ->
            // Addressing complete orders
            conn.prepareStatement("update pending_order set filled_tick = ? where id in ?").use { stmt ->
                stmt.setLong(1, filledTick)
                stmt.setArray(2, conn.createArrayOf("text", completeOrders.map { it.id }.toTypedArray()))
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
                conn.prepareStatement("update pending_order set size = ? where id = ?").use { stmt ->
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
            positionId = conn.prepareStatement(
                "insert into position values ( ?, ?, ?, ? )",
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                stmt.setString(1, order.email)
                stmt.setString(2, order.ticker)
                stmt.setInt(3, order.tradeType.ordinal)
                stmt.setInt(4, order.size)
                stmt.executeUpdate()

                val rs = stmt.generatedKeys
                if (rs.next()) rs.getLong(1) else -1
            }
        }
        return Pair(positionId, filledTick)
    }

    fun createLimitPendingOrder(order: LimitOrderRequest): Pair<Long?, Long> {
        var orderId: Long? = null
        val receivedTick: Long = System.currentTimeMillis()

        db.query { conn ->
            orderId = conn.prepareStatement("""insert into pending_order (user, ticker, trade_type, size, price, order_type, filled_tick, received_tick)
                values (?, ?, ?, ?, ?, ?, ?) 
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

                val rs = stmt.generatedKeys
                if (rs.next()) rs.getLong(1) else -1
            }
        }
        return Pair(orderId, receivedTick)
    }
}
