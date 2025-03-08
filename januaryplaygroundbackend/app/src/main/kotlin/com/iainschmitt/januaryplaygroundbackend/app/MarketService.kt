package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.*
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.BadRequestResponse
import io.javalin.http.NotFoundResponse
import org.slf4j.Logger
import java.sql.Statement
import java.util.concurrent.Semaphore

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

    private val marketDao = MarketDao(db)

    fun orderRequest(order: IncomingOrderRequest):  Either<OrderFailedCode, IOrderFilled> {
        return validateTicker(order.ticker).map { code -> Either.Left(code) }
            .getOrElse {
                val receivedTimestamp = System.currentTimeMillis()
                when (order.orderType) {
                    OrderType.Market -> marketOrderRequest(order)
                    OrderType.Limit -> limitOrderRequest(order, receivedTimestamp)
                    else -> Either.Left(OrderFailedCode.NOT_IMPLEMENTED)
                }
            }
    }

    fun limitOrderRequest(order: IncomingOrderRequest, recievedTimestamp: Long): Either<OrderFailedCode, IOrderFilled> {
        return Either.Left(OrderFailedCode.NOT_IMPLEMENTED)
        transactionSemaphore.acquire()
        try {
            //TODO: Ensure market order timing is recorded for order book to be accurate! Impacts limits too
            //If a market order immediately crossed, an order success should be sent instead of an order ACK


        } finally {
            transactionSemaphore.release()
        }
    }

    fun marketOrderRequest(order: IncomingOrderRequest): Either<OrderFailedCode, IOrderFilled> {
        transactionSemaphore.acquire()
        try {
            val userBalance = marketDao.getUserBalance(order.email)
            if (userBalance == null) return Either.Left(OrderFailedCode.UNKNOWN_TRADER)

            val matchingPendingOrders = getMatchingOrderBook(
                order.ticker,
                if (order.tradeType == TradeType.Buy) TradeType.Sell else TradeType.Buy
            )

            val rMarketOrderProposal = getMarketOrderProposal(order, userBalance, matchingPendingOrders)

            rMarketOrderProposal.mapLeft { code ->
                return Either.Left(code)
                //if (code == OrderFailedCode.INSUFFICIENT_SHARES) {
                //    throw BadRequestResponse("Insufficient shares for order")
                //} else if (code === OrderFailedCode.INSUFFICIENT_BALANCE) {
                //    throw BadRequestResponse("Insufficient balance for order")
                //} else {
                //    logger.error("Error code '${code}' returned from marketOrderProposal")
                //    throw InternalServerErrorResponse("Internal error has occured")
                //}
            }

            // Fix this at some point, this is bad style mixing
            var positionId: Int? = null
            rMarketOrderProposal.map { marketOrderProposal ->
                positionId = marketDao.fillMarketOrder(order, marketOrderProposal)
            }
            transactionSemaphore.release()

            if (positionId != null) {
                val filledTick = System.currentTimeMillis()

                return Either.Right(
                    OrderFilled(
                        order.ticker,
                        positionId!!,
                        filledTick,
                        order.tradeType,
                        order.orderType,
                        order.size,
                        order.email
                    )
                )
            } else {
                logger.error("Position key not set in transaction")
                return Either.Left(OrderFailedCode.INTERNAL_ERROR)
            }
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

    private fun validateTicker(ticker: Ticker): Option<OrderFailedCode> {
        val pair = marketDao.getTicker(ticker)

        if (pair == null) {
            //throw NotFoundResponse("Ticker symbol '${ticker}' not found")
            return Some(OrderFailedCode.UNKNOWN_TICKER)
        } else if (pair.second == 0) {
            //throw BadRequestResponse("Ticker symbol '${ticker}' not open for transactions")
            return Some(OrderFailedCode.MARKET_CLOSED)
        } else return None
    }

    private fun validatePendingOrder(pendingOrderId: Int, email: String): Option<OrderCancelFailedCode> {
        val transactionExists = marketDao.pendingOrderExists(pendingOrderId, email)
        //if (!transactionExists) NotFoundResponse("Transaction '${pendingOrderId}' for user '${email}' not found")

        // Need to disambiguate between removed, filled orders
        if (!transactionExists) return Some(OrderCancelFailedCode.UNKNOWN_ORDER) else return None
    }


    private fun getMatchingOrderBook(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): ArrayList<OrderBookEntry> {
        val matchingPendingOrders = marketDao.getMatchingOrderBook(ticker, pendingOrderTradeType)
        if (pendingOrderTradeType == TradeType.Buy)
            matchingPendingOrders.sortBy { pendingOrder -> pendingOrder.price }
        else matchingPendingOrders.sortByDescending { pendingOrder -> pendingOrder.price }
        return matchingPendingOrders
    }
}
