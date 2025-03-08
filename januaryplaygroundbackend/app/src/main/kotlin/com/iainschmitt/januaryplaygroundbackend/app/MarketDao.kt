package com.iainschmitt.januaryplaygroundbackend.app

import com.iainschmitt.januaryplaygroundbackend.shared.Order
import com.iainschmitt.januaryplaygroundbackend.shared.Ticker
import com.iainschmitt.januaryplaygroundbackend.shared.TradeType
import com.iainschmitt.januaryplaygroundbackend.shared.getOrderType
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
            conn.prepareStatement("select id from pending_order where id = ? and user = ?").use { stmt ->
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
            conn.prepareStatement("select id, user, ticker, price, size, order_type from pending_order where ticker = ? and trade_type = ?")
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
                                    getOrderType(rs.getInt("order_type"))
                                )
                            )
                        }
                    }
                }
        }
        return matchingPendingOrders
    }

    fun fillMarketOrder(order: IncomingOrderRequest, marketOrderProposal: ArrayList<OrderBookEntry>): Int? {
        var positionId: Int? = null
        val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
        val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }
        // From perspective of the counterparties:
        // this is the direction that the counterparty balances will go
        val orderSign = if (order.tradeType == TradeType.Buy) 1 else -1
        db.query { conn ->

            // Addressing complete orders
            conn.prepareStatement("delete from pending_order where id in ?").use { stmt ->
                stmt.setArray(1, conn.createArrayOf("text", completeOrders.map { it.id }.toTypedArray()))
                stmt.executeUpdate()
            }
            // TODO: There is certainly a way to do this in a single query
            for (completeOrder in completeOrders) {
                conn.prepareStatement("update user set balance = balance + ? where email = ?").use { stmt ->
                    stmt.setInt(1, completeOrder.size * completeOrder.price * orderSign)
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
                    stmt.setInt(1, partialOrder.size * partialOrder.price * orderSign)
                    stmt.setString(2, partialOrder.user)
                    stmt.executeUpdate()
                }
            }
            // Addressing orderer
            conn.prepareStatement("update user set balance = balance - ? where email = ?").use { stmt ->
                stmt.setInt(1, marketOrderProposal.sumOf { entry -> entry.size * entry.price } * orderSign)
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
                if (rs.next()) rs.getInt(1) else -1
            }
        }
        return positionId
    }
}
