package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.*
import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.*
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

            val rMarketOrderProposal =
                getMarketOrderProposal(order, userBalance, userLongPositions, getSortedMatchingOrderBook(order))

            return rMarketOrderProposal
                .flatMap { marketOrderProposal -> //a "oh I get it" moment was when the types didn't work on map but did on `flatMap`
                    val positionPair: Pair<Long?, Long> = marketDao.fillMarketOrder(order, marketOrderProposal)
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

    fun limitOrderRequest(order: IncomingLimitOrderRequest): OrderResult<LimitOrderRespponse> {
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
            val crossingOrdersCount = matchingPendingOrders.filter { (price, _) ->
                if (order.isBuy()) price < order.price else price > order.price
            }.map { (_, matchingPendingOrders) -> matchingPendingOrders.size }.sum()

            return when {
                crossingOrdersCount > order.size -> immediatelyFilledLimitOrder(
                    order,
                    matchingPendingOrders,
                    userBalance
                )

                crossingOrdersCount > 0 -> partiallyFilledLimitOrder(order, matchingPendingOrders, userBalance)
                else -> restingLimitOrder(order)
            }

        } finally {
            transactionSemaphore.release()
        }
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun immediatelyFilledLimitOrder(
        order: IncomingLimitOrderRequest,
        matchingPendingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<LimitOrderRespponse> {

        return Either.Left(Pair(OrderFailureCode.NOT_IMPLEMENTED, "Oops"))
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun partiallyFilledLimitOrder(
        order: IncomingLimitOrderRequest,
        matchingPendingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<LimitOrderRespponse> {
        return Either.Left(Pair(OrderFailureCode.NOT_IMPLEMENTED, "Oops"))
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun restingLimitOrder(order: IncomingLimitOrderRequest): OrderResult<LimitOrderRespponse> {
        val orderPair: Pair<Long?, Long> = marketDao.createLimitPendingOrder(order)

        return if (orderPair.first != null) {
            Either.Right(OrderAcknowledged(
                order.ticker,
                orderPair.first!!,
                orderPair.second,
                order.tradeType,
                order.orderType,
                order.size,
                order.email
            ))
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
