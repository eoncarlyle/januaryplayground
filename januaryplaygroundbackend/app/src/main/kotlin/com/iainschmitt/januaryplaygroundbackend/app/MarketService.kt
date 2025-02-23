package com.iainschmitt.januaryplaygroundbackend.app

import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.NotFoundResponse
import org.slf4j.Logger
import java.util.*
import java.util.concurrent.Semaphore
import kotlin.collections.ArrayList

// Ticker, price, size: eventualy should move this to a dedicated class, this is asking for problems
data class PendingOrder(val id: String, val ticker: Ticker, val price: Int, val size: Int, val orderType: OrderType)

class MarketService(
    private val db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger,
    private val transactionSemaphore: Semaphore
) {

    fun orderRequest(order: IncomingOrderRequest) {

        // New tables: ticker, position, store the market status on the ticker itself
        // Add balance to user: integer?

        // DB Notes

        // Validate ticker
        // Validate market open
        // Validate position
        // Put market-order specific functions later down
        validateTicker(order.ticker)

        if(order.orderType == OrderType.Market) {
            marketOrderRequest(order)
        } else {
            throw BadRequestResponse("Order types '${order.orderType}' not yet implemented")
        }
    }

    fun marketOrderRequest(order: IncomingOrderRequest) {
        transactionSemaphore.acquire()
        try {
            val userBalance = db.query { conn ->
                conn.prepareStatement("select balance from user where email = ?").use { stmt ->
                    stmt.setString(1, order.email)
                }
            }

            val matchingPendingOrders = getSortedPendingOrders(
                order.ticker,
                if (order.tradeType == TradeType.Buy) TradeType.Sell else TradeType.Buy
            )

            // Final step: the actual db updates, if multiple statements are needed then `DatabaseHelper` can be updated with multiple queries
            transactionSemaphore.release()
        } finally {
            transactionSemaphore.release()
        }
    }

    private fun marketOrderProposal(
        order: IncomingOrderRequest,
        matchingPendingOrders: ArrayList<PendingOrder>
    ): Array<PendingOrder> {
        if (order.orderType != OrderType.Market) {
            throw BadRequestResponse("Illegal arguments: orders of type '${order.orderType}' not matchable by `marketOrderMatch`")
        }


        // TODO: Pick up here, in the order matching
        var orderGroup = ArrayList<PendingOrder>()
        for (pendingOrder in matchingPendingOrders) {
            var subsequentSize= orderGroup.map { op -> op.size }.sum()
            if ( subsequentSize == pendingOrder.size) {

            } else if (subsequentSize > pendingOrder.size) {
                if (pendingOrder)
            } else {
                orderGroup.add(pendingOrder)
            }
        }
    }

    fun orderCancelRequest(order: IncomingOrderCancelRequest) {

    }

    private fun validateTicker(ticker: Ticker) {
        val pair = db.query { conn ->
            conn.prepareStatement("select symbol, open from ticker where symbol = ?").use { stmt ->
                stmt.setString(1, ticker)
                stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getInt(2)) else null }
            }
        }

        if (pair == null) {
            throw NotFoundResponse("Ticker symbol '${ticker}' not found")
        } else if (pair.second == 0) {
            throw BadRequestResponse("Ticker symbol '${ticker}' not open for transactions")
        }
    }

    private fun validatePendingOrder(orderId: String, email: String) {
        val transactionExists = db.query { conn ->
            conn.prepareStatement("select id from pending_order where id = ? and user = ?").use { stmt ->
                stmt.setString(1, orderId.toString())
                stmt.setString(2, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

        if (!transactionExists) throw NotFoundResponse("Transaction '${orderId}' for user '${email}' not found")
    }


    private fun getSortedPendingOrders(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): ArrayList<PendingOrder> {
        val matchingPendingOrders = ArrayList<PendingOrder>()
        db.query { conn ->
            conn.prepareStatement("select id, ticker, price, size, order_type from pending_order where ticker = ? and trade_type = ?")
                .use { stmt ->
                    stmt.setString(1, ticker)
                    stmt.setInt(
                        2, pendingOrderTradeType.ordinal
                    )
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            matchingPendingOrders.add(
                                PendingOrder(
                                    rs.getString("id"),
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
        if (pendingOrderTradeType == TradeType.Buy)
            matchingPendingOrders.sortBy { pendingOrder -> pendingOrder.price }
        else matchingPendingOrders.sortByDescending { pendingOrder -> pendingOrder.price }
        return matchingPendingOrders
    }

}
