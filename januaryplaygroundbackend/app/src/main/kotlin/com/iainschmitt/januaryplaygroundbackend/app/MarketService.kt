package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.*
import com.iainschmitt.januaryplaygroundbackend.shared.*
import org.slf4j.Logger
import java.util.concurrent.Semaphore
import kotlin.collections.HashMap
import kotlin.math.E

class MarketService(
    private val db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger,
    private val transactionSemaphore: Semaphore
) {

    private val marketDao = MarketDao(db)
    // TODO: Typing is not organized: mix of Model and HttpModel types are used, should be organised
    fun marketOrderRequest(order: IncomingMarketOrderRequest): OrderResult<MarketOrderResponse> {
        transactionSemaphore.acquire()
        try {
            val userBalance = marketDao.getUserBalance(order.email)
                ?: return Either.Left(
                    Pair(
                        OrderFailureCode.UNKNOWN_USER,
                        "Unknown user attempting to transact"
                    )
                )
            val userLongPositions = marketDao.getUserLongPositions(order.email, order.ticker).sum()
            validateTicker(order.ticker).map { orderFailure -> return Either.Left(orderFailure) }

            val marketOrderProposal =
                getMarketOrderProposal(order, userBalance, userLongPositions, getSortedMatchingOrderBook(order))

            return fillOrder(order, marketOrderProposal)
        } finally {
            transactionSemaphore.release()
        }
    }

    private fun fillOrder(
        order: Order,
        orderProposal: Either<OrderFailure, ArrayList<OrderBookEntry>>
    ): Either<OrderFailure, OrderFilled> {
        return orderProposal
            .flatMap { marketOrderProposal -> //a "oh I get it" moment was when the types didn't work on map but did on `flatMap`
                val positionPair: Pair<Long?, Long> = marketDao.fillOrder(order, marketOrderProposal)
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
                    return@flatMap Either.Left(Pair(OrderFailureCode.INTERNAL_ERROR, "An internal error occurred"))
                }
            }
    }

    fun limitOrderRequest(order: IncomingLimitOrderRequest): OrderResult<LimitOrderResponse> {
        transactionSemaphore.acquire()
        try {
            val userBalance = marketDao.getUserBalance(order.email)
                ?: return Either.Left(
                    Pair(
                        OrderFailureCode.UNKNOWN_USER,
                        "Unknown user attempting to transact"
                    )
                )

            val matchingPendingOrders = getSortedMatchingOrderBook(order)
            val crossingOrders: SortedOrderBook = matchingPendingOrders.filter { (price, _) ->
                if (order.isBuy()) price < order.price else price > order.price
            } as SortedOrderBook

            val crossingOrderTotal = getPositionCount(crossingOrders)

            return when {
                crossingOrderTotal > order.size -> immediatelyFilledLimitOrder(
                    order,
                    crossingOrders,
                    userBalance
                )

                crossingOrderTotal > 0 -> partiallyFilledLimitOrder(order, crossingOrders, userBalance)
                else -> createRestingLimitOrder(order)
            }

        } finally {
            transactionSemaphore.release()
        }
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun immediatelyFilledLimitOrder(
        order: IncomingLimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<LimitOrderResponse> {
        val userLongPositions = marketDao.getUserLongPositions(order.email, order.ticker).sum()
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositions, crossingOrders)
        return fillOrder(order, immediateOrderProposal)
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun partiallyFilledLimitOrder(
        order: IncomingLimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<OrderPartiallyFilled> {

        val userLongPositions = marketDao.getUserLongPositions(order.email, order.ticker).sum()
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositions, crossingOrders)

        return fillOrder(order, immediateOrderProposal)
            .flatMap { filledOrder ->
                val resizedOrder = order.getResizedOrder(order.size - getPositionCount(crossingOrders))
                createRestingLimitOrder(resizedOrder).map { restingOrder ->
                    OrderPartiallyFilled(
                        order.ticker,
                        filledOrder.positionId,
                        restingOrder.orderId,
                        filledOrder.filledTime,
                        order.tradeType,
                        order.orderType,
                        order.size,
                        order.email
                    )
                }
            }
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun createRestingLimitOrder(order: IncomingLimitOrderRequest): OrderResult<OrderAcknowledged> {
        val orderPair: Pair<Long?, Long> = marketDao.createLimitPendingOrder(order)

        return if (orderPair.first != null) {
            Either.Right(
                OrderAcknowledged(
                    order.ticker,
                    orderPair.first!!,
                    orderPair.second,
                    order.tradeType,
                    order.orderType,
                    order.size,
                    order.email
                )
            )
        } else {
            Either.Left(Pair(OrderFailureCode.INTERNAL_ERROR, "Internal error"))
        }
    }

    private fun getMarketOrderProposal(
        order: IncomingOrderRequest,
        userBalance: Int,
        userLongPositions: Int,
        sortedOrderBook: SortedOrderBook
    ): Either<OrderFailure, ArrayList<OrderBookEntry>> {
        val proposedOrders = ArrayList<OrderBookEntry>()

        val prices =
            if (order.isBuy()) sortedOrderBook.keys.toList() else sortedOrderBook.keys.toList().sortedDescending()

        for (price in prices) {
            //Probably should change over to some Kotlin collections to avoid all this null checking at some point
            val orderBookEntriesAtPrice = sortedOrderBook[price]
            if (orderBookEntriesAtPrice != null) {
                for (orderBookEntry in orderBookEntriesAtPrice) {
                    val subsequentSize = proposedOrders.sumOf { op -> op.size }
                    if (subsequentSize == order.size) {
                        return getMarketOrderFinalStageOrderProposal(
                            order,
                            proposedOrders,
                            userBalance,
                            userLongPositions
                        )
                    } else if (subsequentSize > order.size) {
                        // If condition not met, have to split
                        if (orderBookEntry.orderType != OrderType.FillOrKill && orderBookEntry.orderType != OrderType.AllOrNothing) {
                            val positionsRemaining = subsequentSize - order.size
                            val finalEntry = orderBookEntry.copy()
                            finalEntry.finalSize = positionsRemaining
                            proposedOrders.add(finalEntry)

                            return getMarketOrderFinalStageOrderProposal(
                                order,
                                proposedOrders,
                                userBalance,
                                userLongPositions
                            )
                        }
                    } else {
                        proposedOrders.add(orderBookEntry)
                    }
                }
            }
        }
        return Either.Left(Pair(OrderFailureCode.INSUFFICIENT_SHARES, "Insufficient shares for order"))
    }

    private fun getMarketOrderFinalStageOrderProposal(
        order: IncomingOrderRequest,
        proposedOrders: ArrayList<OrderBookEntry>,
        userBalance: Int,
        userLongPositions: Int
    ): Either<OrderFailure, ArrayList<OrderBookEntry>> {
        if (order.isBuy()) {
            return if (proposedOrders.sumOf { op -> (op.price * op.size) } <= userBalance) Either.Right(
                proposedOrders
            )
            else Either.Left(
                Pair(
                    OrderFailureCode.INSUFFICIENT_BALANCE,
                    "Insufficient balance for order"
                )
            )
        } else {
            return if (proposedOrders.sumOf { op -> (op.size) } <= userLongPositions) Either.Right(
                proposedOrders
            )
            else Either.Left(
                Pair(
                    OrderFailureCode.INSUFFICIENT_SHARES,
                    "Insufficient shares for order"
                )
            )
        }
    }

    // It will only be necessary to delete all orders of a particular trader to get the market maker working correctly
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

    private fun getPositionCount(sortedOrderBook: SortedOrderBook): Int {
        return sortedOrderBook.map { (_, matchingPendingOrders) -> matchingPendingOrders.size }.sum()
    }

}
