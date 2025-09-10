import arrow.core.*
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.github.reactivecircus.cache4k.Cache
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
    private val cache = Cache.Builder<String, MutableSet<NotificationRule>>()
        .maximumCacheSize(1)
        .build()
    private val notificationRuleCacheKey = "notifications"
    private val notificationSemaphore = Semaphore(1)

    fun marketOrderRequest(order: MarketOrderRequest, writeSemaphore: Semaphore): OrderResult<MarketOrderResponse> =
        withSemaphore(writeSemaphore) {
            return validateOrder(order).map { (validOrder, userBalance) ->
                val userLongPositionCount =
                    exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
                val marketOrderProposal =
                    getMarketOrderProposal(
                        validOrder,
                        userBalance,
                        userLongPositionCount,
                        getSortedMatchingOrderBook(validOrder)
                    )
                return fillOrder(validOrder, marketOrderProposal)
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
                    val receivedTick = System.currentTimeMillis()
                    return@flatMap OrderFilled(
                        order.ticker,
                        filledOrderRecord.positionId,
                        receivedTick,
                        order.tradeType,
                        order.orderType,
                        order.size,
                        order.email
                    ).right()
                } else {
                    logger.error("Position key not set in transaction")
                    return@flatMap Pair(OrderFailureCode.INTERNAL_ERROR, "An internal error occurred").left()
                }
            }
    }

    fun limitOrderRequest(order: LimitOrderRequest, writeSemaphore: Semaphore): OrderResult<LimitOrderResponse> =
        withSemaphore(writeSemaphore) {
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
        }

    fun getState(): Pair<Int, Int> {
        return exchangeDao.getState();
    }

    // Assumes already within transaction semaphore!
    private fun immediatelyFilledLimitOrder(
        order: LimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<LimitOrderResponse> {
        val userLongPositionCount =
            exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositionCount, crossingOrders)
        return fillOrder(order, immediateOrderProposal)
    }

    // Assumes already within transaction semaphore!
    private fun partiallyFilledLimitOrder(
        order: LimitOrderRequest,
        crossingOrders: SortedOrderBook,
        userBalance: Int
    ): OrderResult<OrderPartiallyFilled> {
        val userLongPositionCount =
            exchangeDao.getUserLongPositions(order.email, order.ticker).sumOf { pos -> pos.size }
        val immediateOrderProposal = getMarketOrderProposal(order, userBalance, userLongPositionCount, crossingOrders)

        return fillOrder(order, immediateOrderProposal)
            .flatMap { filledOrder ->
                // This kind of is asking for problems if the `fillOrder` succeeds and the `createRestinLimitOrder` fails
                // TODO: Use result handling to fix the issue above
                val resizedOrder = order.getResizedOrder(order.size - getPositionCount(crossingOrders))
                createRestingLimitOrder(resizedOrder).map { restingOrder ->
                    val receivedTick = System.currentTimeMillis()
                    OrderPartiallyFilled(
                        order.ticker,
                        filledOrder.positionId,
                        restingOrder.orderId,
                        receivedTick,
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
            val receivedTick = System.currentTimeMillis()
            OrderAcknowledged(
                order.ticker,
                orderRecord.orderId,
                receivedTick,
                order.tradeType,
                order.orderType,
                order.size,
                order.email
            ).right()
        } else {
            Pair(OrderFailureCode.INTERNAL_ERROR, "Internal error").left()
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
        return Pair(OrderFailureCode.INSUFFICIENT_SHARES, "Insufficient shares for order").left()
    }

    private fun getMarketOrderFinalStageOrderProposal(
        order: OrderRequest,
        proposedOrders: ArrayList<OrderBookEntry>,
        userBalance: Int,
        userLongPositions: Int
    ): Either<OrderFailure, ArrayList<OrderBookEntry>> =
        if (order.isBuy()) {
            if (proposedOrders.sumOf { op -> (op.size - op.finalSize) * op.price } <= userBalance) proposedOrders.right()
            else Pair(
                OrderFailureCode.INSUFFICIENT_BALANCE,
                "Insufficient balance for order"
            ).left()
        } else {
            if (proposedOrders.sumOf { op -> (op.size - op.finalSize) } <= userLongPositions) proposedOrders.right()
            else Pair(
                OrderFailureCode.INSUFFICIENT_SHARES,
                "Insufficient shares for order"
            ).left()
        }

    // It will only be necessary to delete all orders of a particular trader to get the market maker working correctly
    fun allOrderCancel(
        order: ExchangeRequestDto,
        writeSemaphore: Semaphore
    ): OrderCancelResult<AllOrderCancelFailure, AllOrderCancelResponse> =
        withSemaphore(writeSemaphore) {
            if (exchangeDao.getTicker(order.ticker) == null) {
                return Pair(
                    AllOrderCancelFailureCode.UNKNOWN_TICKER,
                    "Ticker symbol '${order.ticker}' not found"
                ).left()
            }

            val deleteRecord = exchangeDao.deleteAllUserOrders(order.email, order.ticker);

            return when {
                deleteRecord.orderCount > 0 ->
                    AllOrderCancelResponse.SomeOrdersCancelled(
                        order.ticker,
                        deleteRecord.orderCount,
                        System.currentTimeMillis()
                    ).right()

                else -> AllOrderCancelResponse.NoOrdersCancelled(System.currentTimeMillis()).right()
            }
        }

    fun getAllStatelessQuotesInLock(lightswitch: Lightswitch): List<StatelessQuote> =
        withLightswitch(lightswitch) { exchangeDao.getAllStatelessQuotes() }

    fun getStatelessQuoteOutsideLock(ticker: Ticker, lightswitch: Lightswitch): StatelessQuote? =
        withLightswitch(lightswitch) {
            return exchangeDao.getStatelessQuote(ticker)
        }


    fun getUserBalance(userEmail: String, lightswitch: Lightswitch): Int? = withLightswitch(lightswitch) {
        return exchangeDao.getUserBalance(userEmail)
    }

    fun getUserLongPositions(userEmail: String, ticker: Ticker, lightswitch: Lightswitch): List<PositionRecord> =
        withLightswitch(lightswitch) {
            return exchangeDao.getUserLongPositions(userEmail, ticker)
        }

    fun getUserOrders(userEmail: String, ticker: Ticker, lightswitch: Lightswitch): List<OrderBookEntry> =
        withLightswitch(lightswitch) {
            return exchangeDao.getUserOrders(userEmail, ticker)
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
        if (order is LimitOrderRequest) {
            ensure(order.price > 0) {
                Pair(
                    OrderFailureCode.BAD_PRICE,
                    "Price must be greater than zero, ${order.price} provided"
                )
            }
        }
        ValidOrderRecord(order, userBalance)
    }

    // Kotlin talk: `T` can be unit, void is not special
    private inline fun <T> withSemaphore(semaphore: Semaphore, action: () -> T): T {
        semaphore.acquire()
        try {
            return action()
        } finally {
            semaphore.release()
        }
    }

    private inline fun <T> withNotificationSemaphore(action: () -> T): T = withSemaphore(notificationSemaphore, action)

    private inline fun <T> withLightswitch(lightswitch: Lightswitch, action: () -> T): T {
        lightswitch.unlock()
        try {
            return action()
        } finally {
            lightswitch.lock()
        }
    }

    fun getNotificationRules(): Set<NotificationRule> = withNotificationSemaphore {
        cache.get(notificationRuleCacheKey) ?: run {
            val rules = exchangeDao.getNotificationRules()
            cache.put(notificationRuleCacheKey, rules)
            rules
        }
    }

    fun createNotificationRule(rule: NotificationRule) = withNotificationSemaphore {
        exchangeDao.createNotificationRule(rule)
        cache.put(notificationRuleCacheKey, exchangeDao.getNotificationRules())
    }

    fun deleteNotificationRule(rule: NotificationRule): Boolean = withNotificationSemaphore {
        val cachedRules = cache.get(notificationRuleCacheKey)
        if (cachedRules?.contains(rule) == true) {
            exchangeDao.deleteNotificationRule(rule)
            val updatedRules = cachedRules.toMutableSet().apply { remove(rule) }
            cache.put(notificationRuleCacheKey, updatedRules)
            true
        } else false
    }

    private fun validatePendingOrder(pendingOrderId: Int, email: String): Option<SingleOrderCancelFailureCode> {
        val transactionExists = exchangeDao.unfilledOrderExists(pendingOrderId, email)

        return if (!transactionExists) Some(SingleOrderCancelFailureCode.UNKNOWN_ORDER) else None
    }

    fun validateTicker(ticker: Ticker): Boolean {
        return exchangeDao.getTicker(ticker) != null
    }

    private fun getSortedMatchingOrderBook(
        order: OrderRequest
    ): SortedOrderBook {
        val matchingPendingOrders = exchangeDao.getMatchingOrderBook(order.ticker, order.tradeType)
        val book: SortedOrderBook = HashMap()

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
