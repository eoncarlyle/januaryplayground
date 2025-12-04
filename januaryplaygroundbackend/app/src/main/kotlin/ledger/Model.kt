package ledger

import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlinx.serialization.Serializable
import java.util.concurrent.CompletableFuture

sealed class LedgerK {
    @Serializable
    data class Tickers(
        val symbol: Ticker
    ) : LedgerK()

    @Serializable
    data class Users(
        val email: String
    ) : LedgerK()

    @Serializable
    data class Sessions(
        val token: String
    ) : LedgerK()

    @Serializable
    data class OrderRecords(
        val id: Long //Safe ID creation will be important
    ) : LedgerK()

    @Serializable
    data class PositionRecords(
        val userEmail: String,
        val ticker: Ticker,
        val positionType: PositionType,
    ) : LedgerK()

    @Serializable
    data class NotificationRules(
        val userEmail: String
    ) : LedgerK()
}

sealed class LedgerV {
    @Serializable
    data class Tickers(
        val open: Boolean
    ) : LedgerV()

    @Serializable
    data class Users(
        val passwordHash: String,
        val balance: Int,
        val type: AccountType,
        val orchestratedBy: String?
    ) : LedgerV()

    @Serializable
    data class Sessions(
        val expireTimestamp: Long,
        val email: String
    ) : LedgerV()

    @Serializable
    data class OrderRecords(
        val userEmail: String,
        val ticker: Ticker,
        val tradeType: TradeType,
        val size: Int,
        val price: Int,
        val orderType: OrderType,
        val filledTick: Long,
        val receivedTick: Long
    ) : LedgerV()

    @Serializable
    data class PositionRecords(
        val size: Int,
        val receivedTick: Long
    ) : LedgerV()

    @Serializable
    data class NotificationRules(
        val category: NotificationCategory,
        val operation: NotificationOperation,
        val timestamp: Long
    ) : LedgerV()
}

@Serializable
sealed class LedgerTableEntry {
    data class Tickers(val key: LedgerK.Tickers, val value: LedgerV.Tickers) :
        LedgerTableEntry()

    data class Users(val key: LedgerK.Users, val value: LedgerV.Users) :
        LedgerTableEntry()

    data class Sessions(val key: LedgerK.Sessions, val value: LedgerV.Sessions) :
        LedgerTableEntry()

    data class OrderRecords(val key: LedgerK.OrderRecords, val value: LedgerV.OrderRecords) :
        LedgerTableEntry()

    data class PositionRecords(val key: LedgerK.PositionRecords, val value: LedgerV.PositionRecords) :
        LedgerTableEntry()

    data class NotificationRules(val key: LedgerK.NotificationRules, val value: LedgerV.NotificationRules) :
        LedgerTableEntry()
}

@Serializable
sealed class LedgerTableOperationType {
    data object Create : LedgerTableOperationType()
    data object Update : LedgerTableOperationType()
    data object Delete : LedgerTableOperationType()
}

@Serializable
data class LedgerTableOperation(val entry: LedgerTableEntry, val type: LedgerTableOperationType)

//Kotlin limitation
fun LedgerTableEntry.getKey(): LedgerK = when (this) {
    is LedgerTableEntry.Tickers -> this.key
    is LedgerTableEntry.Users -> this.key
    is LedgerTableEntry.Sessions -> this.key
    is LedgerTableEntry.OrderRecords -> this.key
    is LedgerTableEntry.PositionRecords -> this.key
    is LedgerTableEntry.NotificationRules -> this.key
}

fun LedgerTableEntry.getValue(): LedgerV = when (this) {
    is LedgerTableEntry.Tickers -> this.value
    is LedgerTableEntry.Users -> this.value
    is LedgerTableEntry.Sessions -> this.value
    is LedgerTableEntry.OrderRecords -> this.value
    is LedgerTableEntry.PositionRecords -> this.value
    is LedgerTableEntry.NotificationRules -> this.value
}

class LedgerRequestEntry<T>(
    private val future: CompletableFuture<T>,
    private val stateChangeAndResult: () -> T,
) {
    fun execute() {
        try {
            future.complete(stateChangeAndResult())
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }
    }
}

fun userLedgerOperation(
    operation: LedgerTableOperationType,
    email: String,
    passwordHash: String,
    balance: Int,
    accountType: AccountType,
    orchestratedBy: String?
) = LedgerTableOperation(
    LedgerTableEntry.Users(
        LedgerK.Users(email), LedgerV.Users(passwordHash, balance, accountType, orchestratedBy)
    ), operation
)

fun userLedgerBalanceUpdate(
    email: String,
    value: LedgerV.Users,
    newBalance: Int,
) = LedgerTableOperation(
    LedgerTableEntry.Users(
        LedgerK.Users(email), LedgerV.Users(value.passwordHash, newBalance, value.type, value.orchestratedBy)
    ), LedgerTableOperationType.Update
)

fun positionLedgerOperation(
    operation: LedgerTableOperationType,
    userEmail: String,
    ticker: Ticker,
    positionType: PositionType,
    size: Int,
    receivedTick: Long
) = LedgerTableOperation(
    LedgerTableEntry.PositionRecords(
        LedgerK.PositionRecords(userEmail, ticker, positionType),
        LedgerV.PositionRecords(size, receivedTick)
    ), operation
)

fun sessionLedgerOperation(
    operation: LedgerTableOperationType,
    token: String,
    expireTimestamp: Long,
    email: String
) = LedgerTableOperation(
    LedgerTableEntry.Sessions(
        LedgerK.Sessions(token), LedgerV.Sessions(expireTimestamp, email)
    ), operation
)

fun orderLedgerOperation(
    operation: LedgerTableOperationType,
    id: Int,
    userEmail: String,
    ticker: Ticker,
    tradeType: TradeType,
    size: Int,
    price: Int,
    orderType: OrderType,
    filledTick: Long,
    receivedTick: Long
) = LedgerTableOperation(
    LedgerTableEntry.OrderRecords(
        LedgerK.OrderRecords(id.toLong()),
        LedgerV.OrderRecords(
            userEmail, ticker, tradeType, size, price, orderType, filledTick, receivedTick
        )
    ), operation
)

fun tickerLedgerOperation(
    symbol: Ticker,
    open: Boolean,
    operation: LedgerTableOperationType
) = LedgerTableOperation(
    LedgerTableEntry.Tickers(
        LedgerK.Tickers(symbol),
        LedgerV.Tickers(open),
    ), operation
)
