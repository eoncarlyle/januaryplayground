package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.Either
import arrow.core.constant
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.InternalServerErrorResponse
import io.javalin.http.NotFoundResponse
import org.slf4j.Logger
import java.util.concurrent.Semaphore
import kotlin.collections.ArrayList

// Ticker, price, size: eventualy should move this to a dedicated class, this is asking for problems
data class OrderBookEntry(
    val id: Int,
    val user: String,
    val ticker: Ticker,
    val price: Int,
    val size: Int,
    val orderType: OrderType,
    var finalSize: Int = 0
)

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

        if (order.orderType == OrderType.Market) {
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
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt("balance") else null }
                }
            }
            if (userBalance == null) throw NotFoundResponse("User '${order.email}' not found")

            val matchingPendingOrders = getMatchingOrderBook(
                order.ticker,
                if (order.tradeType == TradeType.Buy) TradeType.Sell else TradeType.Buy
            )

            val rMarketOrderProposal = getMarketOrderProposal(order, userBalance, matchingPendingOrders)

            rMarketOrderProposal.mapLeft { code ->
                if (code == OrderFailedCode.INSUFFICIENT_SHARES) {
                    throw BadRequestResponse("Insufficient shares for order")
                } else if (code === OrderFailedCode.INSUFFICIENT_BALANCE) {
                    throw BadRequestResponse("Insufficient balance for order")
                } else {
                    logger.error("Error code '${code}' returned from marketOrderProposal")
                    throw InternalServerErrorResponse("Internal error has occured")
                }
            }

            rMarketOrderProposal.map { marketOrderProposal ->

                val partialOrders = marketOrderProposal.filter { entry -> entry.finalSize != 0 }
                val completeOrders = marketOrderProposal.filter { entry -> entry.finalSize == 0 }
                // From perspective of the counterparties:
                // this is the direction that the counterparty balances will go
                val orderSign = if (order.tradeType == TradeType.Buy) 1 else -1

                db.query { conn ->

                    // Addressing complete orders
                    conn.prepareStatement("delete pending_order where id in ?").use { stmt ->
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
                    conn.prepareStatement("insert into position values ( ?, ?, ?, ? )").use { stmt ->
                        stmt.setString(1, order.email)
                        stmt.setString(2, order.ticker)
                        stmt.setInt(3, order.tradeType.ordinal)
                        stmt.setInt(4, order.size)
                    }
                }
            }
            transactionSemaphore.release()
        } finally {
            transactionSemaphore.release()
        }
    }

    private fun getMarketOrderProposal(
        order: IncomingOrderRequest,
        traderBalance: Int,
        matchingOrderBook: ArrayList<OrderBookEntry>
    ): Either<OrderFailedCode, ArrayList<OrderBookEntry>> {
        if (order.orderType != OrderType.Market) {
            throw BadRequestResponse("Illegal arguments: orders of type '${order.orderType}' not matchable by `marketOrderMatch`")
        }

        val proposedOrders = ArrayList<OrderBookEntry>()
        for (orderBookEntry in matchingOrderBook) {
            val subsequentSize = proposedOrders.sumOf { op -> op.size }
            if (subsequentSize == order.size) {
                return if (proposedOrders.sumOf { op -> (op.price * op.size) } <= traderBalance) Either.Right(
                    proposedOrders
                )
                else Either.Left(OrderFailedCode.INSUFFICIENT_BALANCE)
            } else if (subsequentSize > order.size) {
                // If condition not met, have to split
                if (orderBookEntry.orderType != OrderType.FillOrKill && orderBookEntry.orderType != OrderType.AllOrNothing) {
                    val positionsRemaining = subsequentSize - order.size
                    val finalEntry = orderBookEntry.copy()
                    finalEntry.finalSize = positionsRemaining
                    proposedOrders.add(finalEntry)

                    return if (proposedOrders.sumOf { op -> (op.price * op.size) } <= traderBalance) Either.Right(
                        proposedOrders
                    )
                    else Either.Left(OrderFailedCode.INSUFFICIENT_BALANCE)
                }
            } else {
                proposedOrders.add(orderBookEntry)
            }
        }
        return Either.Left(OrderFailedCode.INSUFFICIENT_SHARES)
    }

    fun orderCancelRequest(order: IncomingOrderCancelRequest) {
        // Will need to include/reference any partial execution of an order between submission and cancelation request
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

    private fun validatePendingOrder(pendingOrderId: Int, email: String) {
        val transactionExists = db.query { conn ->
            conn.prepareStatement("select id from pending_order where id = ? and user = ?").use { stmt ->
                stmt.setInt(1, pendingOrderId)
                stmt.setString(2, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }

        if (!transactionExists) throw NotFoundResponse("Transaction '${pendingOrderId}' for user '${email}' not found")
    }


    private fun getMatchingOrderBook(
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
        if (pendingOrderTradeType == TradeType.Buy)
            matchingPendingOrders.sortBy { pendingOrder -> pendingOrder.price }
        else matchingPendingOrders.sortByDescending { pendingOrder -> pendingOrder.price }
        return matchingPendingOrders
    }

}
