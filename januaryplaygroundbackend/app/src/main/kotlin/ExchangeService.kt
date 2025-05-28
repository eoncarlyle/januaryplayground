import arrow.core.*
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.iainschmitt.januaryplaygroundbackend.shared.*
import org.slf4j.Logger
import java.util.concurrent.Semaphore
import kotlin.collections.HashMap

class ExchangeService(
    db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger,
) {

    private val exchangeDao = ExchangeDao(db)
    fun marketOrderRequest(order: MarketOrderRequest, semaphore: Semaphore): OrderResult<MarketOrderResponse> {
        semaphore.acquire()
        try {
            return validateOrder(order).map { (validOrder, userBalance) ->
                val userLongPositionCount = exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
                val marketOrderProposal =
                    getMarketOrderProposal(
                        validOrder,
                        userBalance,
                        userLongPositionCount,
                        getSortedMatchingOrderBook(validOrder)
                    )
                return fillOrder(validOrder, marketOrderProposal)
            }
        } finally {
            semaphore.release()
        }
    }

    private fun fillOrder(
        order: Order,
        orderProposal: Either<OrderFailure, ArrayList<OrderBookEntry>>
    ): Either<OrderFailure, OrderFilled> {
        return orderProposal
            .flatMap { marketOrderProposal ->
                val filledOrderRecord = exchangeDao.fillOrder(order, marketOrderProposal)
                if (filledOrderRecord != null) {
                    return@flatMap Either.Right(
                        OrderFilled(
                            order.ticker,
                            filledOrderRecord.positionId,
                            filledOrderRecord.filledTick,
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

    fun limitOrderRequest(order: LimitOrderRequest, semaphore: Semaphore): OrderResult<LimitOrderResponse> {
        semaphore.acquire()
        try {
            return validateOrder(order).map { (validOrder, userBalance) ->
                val matchingPendingOrders = getSortedMatchingOrderBook(validOrder)
                val crossingOrders: SortedOrderBook = matchingPendingOrders.filter { (price, _) ->
                    if (validOrder.isBuy()) price < validOrder.price else price > validOrder.price
                } as SortedOrderBook

                val crossingOrderTotal = getPositionCount(crossingOrders)

                return when {
                    crossingOrderTotal >= validOrder.size -> immediatelyFilledLimitOrder(
                        validOrder,
                        crossingOrders,
                        userBalance
                    )

                    crossingOrderTotal > 0 -> partiallyFilledLimitOrder(validOrder, crossingOrders, userBalance)
                    else -> createRestingLimitOrder(validOrder)
                }
            }
        } finally {
            semaphore.release()
        }
    }

    fun getState(): Pair<Int, Int> {
        return exchangeDao.getState();
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun immediatelyFilledLimitOrder(
        order: LimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<LimitOrderResponse> {
        val userLongPositionCount = exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositionCount, crossingOrders)
        return fillOrder(order, immediateOrderProposal)
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun partiallyFilledLimitOrder(
        order: LimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<OrderPartiallyFilled> {
        val userLongPositionCount = exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositionCount, crossingOrders)

        return fillOrder(order, immediateOrderProposal)
            .flatMap { filledOrder ->
                // This kind of is asking for problems if the `fillOrder` succeeds and the `createRestinLimitOrder` fails
                // TODO: Use result handling to fix the issue above
                val resizedOrder = order.getResizedOrder(order.size - getPositionCount(crossingOrders))
                createRestingLimitOrder(resizedOrder).map { restingOrder ->
                    OrderPartiallyFilled(
                        order.ticker,
                        filledOrder.positionId,
                        restingOrder.orderId,
                        filledOrder.filledTick,
                        order.tradeType,
                        order.orderType,
                        order.size,
                        order.email
                    )
                }
            }
    }

    // Assumes already within transaction semaphore, probably terrible idea
    private fun createRestingLimitOrder(order: LimitOrderRequest): OrderResult<OrderAcknowledged> {
        val orderRecord = exchangeDao.createLimitPendingOrder(order)
        //LimitPendingOrderRecord

        return if (orderRecord != null) {
            Either.Right(
                OrderAcknowledged(
                    order.ticker,
                    orderRecord.orderId,
                    orderRecord.receivedTick,
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
        order: OrderRequest,
        userBalance: Int,
        userLongPositionCount: Int,
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
                    val subsequentSize = proposedOrders.sumOf { op -> op.size } + orderBookEntry.size
                    if (subsequentSize == order.size) {
                        proposedOrders.add(orderBookEntry)
                        return getMarketOrderFinalStageOrderProposal(
                            order,
                            proposedOrders,
                            userBalance,
                            userLongPositionCount
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
                                userLongPositionCount
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
        order: OrderRequest,
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
    fun allOrderCancel(
        order: ExchangeRequestDto,
        semaphore: Semaphore
    ): OrderCancelResult<AllOrderCancelFailure, AllOrderCancelResponse> {
        semaphore.acquire()
        try {
            if (exchangeDao.getTicker(order.ticker) == null) {
                return Either.Left(
                    Pair(
                        AllOrderCancelFailureCode.UNKNOWN_TICKER,
                        "Ticker symbol '${order.ticker}' not found"
                    )
                )
            }

            val deleteRecord = exchangeDao.deleteAllUserOrders(order.email, order.ticker);

            return when {
                deleteRecord.orderCount > 0 -> Either.Right(
                    AllOrderCancelResponse(
                        order.ticker,
                        deleteRecord.cancelledTick,
                        deleteRecord.orderCount
                    )
                )

                else -> Either.Left(
                    AllOrderCancelFailure(
                        AllOrderCancelFailureCode.INSUFFICIENT_SHARES,
                        "No unfilled orders to cancel"
                    )
                )
            }

        } finally {
            semaphore.release()
        }
    }

    fun getQuote(ticker: Ticker): Quote? {
        return exchangeDao.getQuote(ticker)
    }

    fun getUserLongPositions(user: String, ticker: Ticker): List<PositionRecord> {
        return exchangeDao.getUserLongPositions(user, ticker)
    }

    fun getUserOrders(user: String, ticker: Ticker): List<OrderBookEntry> {
        return exchangeDao.getUserOrders(user, ticker)
    }

    private fun <T : OrderRequest> validateOrder(order: T): Either<OrderFailure, ValidOrderRecord<T>> = either {
        val tickerRecord = exchangeDao.getTicker(order.ticker)
        ensure(tickerRecord != null) {
            Pair(
                OrderFailureCode.UNKNOWN_TICKER,
                "Ticker symbol '${order.ticker}' not found"
            )
        }
        ensure(tickerRecord.open) {
            Pair(
                OrderFailureCode.MARKET_CLOSED,
                "Ticker symbol '${order.ticker}' not open for transactions"
            )
        }
        val userBalance = exchangeDao.getUserBalance(order.email)
        ensure(userBalance != null) { Pair(OrderFailureCode.UNKNOWN_USER, "Unknown user attempting to transact") }
        ValidOrderRecord(order, userBalance)
    }

    private fun validatePendingOrder(pendingOrderId: Int, email: String): Option<SingleOrderCancelFailureCode> {
        val transactionExists = exchangeDao.unfilledOrderExists(pendingOrderId, email)
        //if (!transactionExists) NotFoundResponse("Transaction '${pendingOrderId}' for user '${email}' not found")

        // Need to disambiguate between removed, filled orders
        return if (!transactionExists) Some(SingleOrderCancelFailureCode.UNKNOWN_ORDER) else None
    }

    private fun getSortedMatchingOrderBook(
        order: OrderRequest
    ): SortedOrderBook {
        val matchingPendingOrders = exchangeDao.getMatchingOrderBook(order.ticker, order.tradeType)
        val book: SortedOrderBook = HashMap()
        // Previous function was much clunkier
        matchingPendingOrders.forEach { entry ->
            book.getOrPut(entry.price) { ArrayList() }.add(entry)
        }

        book.forEach { (price, orders) ->
            book[price] = ArrayList(orders.sortedBy { it.receivedTick })
        }
        return book
    }

    private fun getPositionCount(sortedOrderBook: SortedOrderBook): Int {
        return sortedOrderBook.map { (_, matchingPendingOrders) -> matchingPendingOrders.size }.sum()
    }
}
