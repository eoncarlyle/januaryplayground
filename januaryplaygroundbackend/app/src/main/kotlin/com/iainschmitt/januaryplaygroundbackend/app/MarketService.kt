package com.iainschmitt.januaryplaygroundbackend.app

import arrow.core.*
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.BadRequestResponse
import org.slf4j.Logger
import java.util.HashMap
import java.util.concurrent.Semaphore

class MarketService(
    private val db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger,
    private val transactionSemaphore: Semaphore
) {

    private val marketDao = MarketDao(db)

    fun orderRequest(order: IncomingOrderRequest): Either<OrderFailedCode, IOrderFilled> {
        return validateTicker(order.ticker).map { code -> Either.Left(code) }
            .getOrElse {
                val receivedTimestamp = System.currentTimeMillis()
                when (order.orderType) {
                    OrderType.Market -> marketOrderRequest(order)
                    //OrderType.Limit -> limitOrderRequest(order, receivedTimestamp)
                    else -> Either.Left(OrderFailedCode.NOT_IMPLEMENTED)
                }
            }
    }

    fun limitOrderRequest(order: IncomingOrderRequest): Either<OrderFailedCode, IOrderFilled> {
        transactionSemaphore.acquire()
        try {
            //TODO: Ensure market order timing is recorded for order book to be accurate! Impacts limits too
            //If a market order immediately crossed, an order success should be sent instead of an order ACK

            // 1) Check if crosses book
            // 2) Need to populate receivedTimestamp
            // 3)


        } finally {
            transactionSemaphore.release()
        }
    }

    fun marketOrderRequest(order: IncomingOrderRequest): Either<OrderFailedCode, IOrderFilled> {
        transactionSemaphore.acquire()
        try {
            val userBalance = marketDao.getUserBalance(order.email)
            if (userBalance == null) return Either.Left(OrderFailedCode.UNKNOWN_TRADER)

            val matchingPendingOrders = getSortedMatchingOrderBook(
                order.ticker,
                if (order.tradeType == TradeType.Buy) TradeType.Sell else TradeType.Buy
            )

            val rMarketOrderProposal = getMarketOrderProposal(order, userBalance, matchingPendingOrders)

            return rMarketOrderProposal.mapLeft { code ->
                return@mapLeft code
                //if (code == OrderFailedCode.INSUFFICIENT_SHARES) {
                //    throw BadRequestResponse("Insufficient shares for order")
                //} else if (code === OrderFailedCode.INSUFFICIENT_BALANCE) {
                //    throw BadRequestResponse("Insufficient balance for order")
                //} else {
                //    logger.error("Error code '${code}' returned from marketOrderProposal")
                //    throw InternalServerErrorResponse("Internal error has occured")
                //}
            }
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
                        return@flatMap Either.Left(OrderFailedCode.INTERNAL_ERROR)
                    }
                }
        } finally {
            transactionSemaphore.release()
        }
    }

    private fun getMarketOrderProposal(
        order: IncomingOrderRequest,
        traderBalance: Int,
        sortedOrderBook: SortedOrderBook
    ): Either<OrderFailedCode, ArrayList<OrderBookEntry>> {
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
            }
            return Either.Left(OrderFailedCode.INSUFFICIENT_SHARES)
        }
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
        return if (!transactionExists) Some(OrderCancelFailedCode.UNKNOWN_ORDER) else None
    }

    private fun getSortedMatchingOrderBook(
        ticker: Ticker,
        pendingOrderTradeType: TradeType
    ): SortedOrderBook {
        val matchingPendingOrders = marketDao.getMatchingOrderBook(ticker, pendingOrderTradeType)
        val book: SortedOrderBook = HashMap();
        matchingPendingOrders.forEach { entry ->
            if (book.containsKey(entry.price)) {
                //Probably should change over to some Kotlin collections to avoid all this null checking at some point
                val ordersAtPrice = book[entry.price]
                if (ordersAtPrice != null) {
                    ordersAtPrice.add(entry)
                    book[entry.price] = ordersAtPrice
                }
            } else {
                book[entry.price] = arrayListOf(entry)
            }
        }
        book.keys.forEach{price ->
            book[price]?.sortedBy { entry -> entry.receivedTick }
        }
        return book
    }
}
