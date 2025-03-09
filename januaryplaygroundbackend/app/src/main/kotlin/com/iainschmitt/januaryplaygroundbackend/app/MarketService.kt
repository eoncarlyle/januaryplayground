package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.*
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.BadRequestResponse
import org.slf4j.Logger
import java.util.concurrent.Semaphore
import kotlin.collections.HashMap

class MarketService(
    private val db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger,
    private val transactionSemaphore: Semaphore
) {

    private val marketDao = MarketDao(db)

    fun marketOrderRequest(order: IncomingMarketOrderRequest): OrderResult {
        transactionSemaphore.acquire()
        try {
            val userBalance = marketDao.getUserBalance(order.email)
                ?: return Either.Left(
                    Pair(
                        OrderFailureCode.UNKNOWN_TRADER,
                        "Unknown trader attempting to transact"
                    )
                )
            validateTicker(order.ticker).map { orderFailure -> return Either.Left(orderFailure) }

            val matchingPendingOrders = getSortedMatchingOrderBook(order)
            val rMarketOrderProposal = getMarketOrderProposal(order, userBalance, matchingPendingOrders)

            return rMarketOrderProposal
                .flatMap { marketOrderProposal -> //a "oh I get it" moment was when the types didn't work on map but did on `flatMap`
                    val positionPair: Pair<Int?, Long> = marketDao.fillMarketOrder(order, marketOrderProposal)
                    if (positionPair.first != null) {
                        return@flatMap Either.Right(
                            OrderFilled(
                                order.ticker,
                                positionPair.first!!,
                                positionPair.second,
                                order.tradeType,
                                order.orderType,
                                order.size,
                                order.email
                            )
                        )
                    } else {
                        logger.error("Position key not set in transaction")
                        return@flatMap Either.Left(Pair(OrderFailureCode.INTERNAL_ERROR, "An internal error occured"))
                    }
                }
        } finally {
            transactionSemaphore.release()
        }
    }

    fun limitOrderRequest(order: IncomingMarketOrderRequest): Either<OrderFailureCode, IOrderFilled> {
        transactionSemaphore.acquire()
        try {
            //TODO: Ensure market order timing is recorded for order book to be accurate! Impacts limits too
            //If a market order immediately crossed, an order success should be sent instead of an order ACK

            // 1) Check if crosses book
            // 2) Need to populate receivedTimestamp
            // 3)

            val matchingPendingOrders = getSortedMatchingOrderBook(order)


        } finally {
            transactionSemaphore.release()
        }
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun restingMarketOrder(order: IncomingMarketOrderRequest): OrderResult {

    }

    private fun getMarketOrderProposal(
        order: IncomingMarketOrderRequest,
        traderBalance: Int,
        sortedOrderBook: SortedOrderBook
    ): Either<OrderFailure, ArrayList<OrderBookEntry>> {
        if (order.orderType != OrderType.Market) {
            throw BadRequestResponse("Illegal arguments: orders of type '${order.orderType}' not matchable by `marketOrderMatch`")
        }

        val proposedOrders = ArrayList<OrderBookEntry>()

        val prices = if (order.tradeType == TradeType.Buy) sortedOrderBook.keys.toList()
            .sorted() else sortedOrderBook.keys.toList().sortedDescending()

        for (price in prices) {
            //Probably should change over to some Kotlin collections to avoid all this null checking at some point
            val orderBookEntriesAtPrice = sortedOrderBook[price]
            if (orderBookEntriesAtPrice != null) {
                for (orderBookEntry in orderBookEntriesAtPrice) {
                    val subsequentSize = proposedOrders.sumOf { op -> op.size }
                    if (subsequentSize == order.size) {
                        return if (proposedOrders.sumOf { op -> (op.price * op.size) } <= traderBalance) Either.Right(
                            proposedOrders
                        )
                        else Either.Left(Pair(OrderFailureCode.INSUFFICIENT_BALANCE, "Insufficient balance for order"))
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
                            else Either.Left(
                                Pair(
                                    OrderFailureCode.INSUFFICIENT_BALANCE,
                                    "Insufficient balance for order"
                                )
                            )
                        }
                    } else {
                        proposedOrders.add(orderBookEntry)
                    }
                }
            }
            return Either.Left(Pair(OrderFailureCode.INSUFFICIENT_SHARES, "Insufficient shares for order"))
        }
    }

    fun orderCancelRequest(order: IncomingOrderCancelRequest) {
        // Will need to include/reference any partial execution of an order between submission and cancelation request
    }

    private fun validateTicker(ticker: Ticker): Option<OrderFailure> {
        val pair = marketDao.getTicker(ticker)

        return if (pair == null) {
            Some(Pair(OrderFailureCode.UNKNOWN_TICKER, "Ticker symbol '${ticker}' not found"))
        } else if (pair.second == 0) {
            Some(Pair(OrderFailureCode.MARKET_CLOSED, "Ticker symbol '${ticker}' not open for transactions"))
        } else None
    }

    private fun validatePendingOrder(pendingOrderId: Int, email: String): Option<OrderCancelFailedCode> {
        val transactionExists = marketDao.pendingOrderExists(pendingOrderId, email)
        //if (!transactionExists) NotFoundResponse("Transaction '${pendingOrderId}' for user '${email}' not found")

        // Need to disambiguate between removed, filled orders
        return if (!transactionExists) Some(OrderCancelFailedCode.UNKNOWN_ORDER) else None
    }

    private fun getSortedMatchingOrderBook(
        order: IncomingOrderRequest
    ): SortedOrderBook {
        val matchingPendingOrders = marketDao.getMatchingOrderBook(order.ticker, order.tradeType)
        val book: SortedOrderBook = HashMap();
        // Previous function was much clunkier
        matchingPendingOrders.forEach { entry ->
            book.getOrPut(entry.price) { ArrayList() }.add(entry)
        }

        book.forEach { (price, orders) ->
            book[price] = orders.sortedBy { it.receivedTick } as ArrayList
        }
        return book
    }
}
