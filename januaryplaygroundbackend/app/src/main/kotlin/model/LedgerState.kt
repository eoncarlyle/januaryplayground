package model

import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlinx.serialization.Serializable

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

class LedgerState(
    private val tickers: MutableMap<LedgerK.Tickers, LedgerV.Tickers> = mutableMapOf(),
    private val users: MutableMap<LedgerK.Users, LedgerV.Users> = mutableMapOf(),
    private val sessions: MutableMap<LedgerK.Sessions, LedgerV.Sessions> = mutableMapOf(),
    private val orderRecords: MutableMap<LedgerK.OrderRecords, LedgerV.OrderRecords> = mutableMapOf(),
    private val positionRecords: MutableMap<LedgerK.PositionRecords, LedgerV.PositionRecords> = mutableMapOf(),
    private val notificationRules: MutableMap<LedgerK.NotificationRules, LedgerV.NotificationRules> = mutableMapOf()
) {
    var nextOrderId = 0L

    fun apply(ledgerTableOperations: List<LedgerTableOperation>) = ledgerTableOperations.forEach { apply(it) }

    private fun apply(ledgerTableOperation: LedgerTableOperation) {
        when (ledgerTableOperation.type) {
            is LedgerTableOperationType.Create -> {
                when (ledgerTableOperation.entry) {
                    is LedgerTableEntry.OrderRecords -> {
                        upsert(
                            LedgerTableOperation(
                                LedgerTableEntry.OrderRecords(
                                    LedgerK.OrderRecords(nextOrderId++),
                                    ledgerTableOperation.entry.getValue() as LedgerV.OrderRecords
                                ), LedgerTableOperationType.Create
                            )
                        )
                    }
                    else -> upsert(ledgerTableOperation)
                }
            }

            is LedgerTableOperationType.Update -> {
                upsert(ledgerTableOperation)
            }

            is LedgerTableOperationType.Delete -> {
                getTable(ledgerTableOperation.entry).remove(ledgerTableOperation.entry.getKey())
            }
        }
    }

    private fun upsert(ledgerTableOperation: LedgerTableOperation) {
        //This is absolutely heinous and yes I inlined things that I didn't need to because I could not bear this...
        //...function taking any more lines
        when (ledgerTableOperation.entry) {
            is LedgerTableEntry.Tickers -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.Tickers, LedgerV.Tickers>)[ledgerTableOperation.entry.getKey() as LedgerK.Tickers] =
                ledgerTableOperation.entry.getValue() as LedgerV.Tickers
            is LedgerTableEntry.Users -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.Users, LedgerV.Users>)[ledgerTableOperation.entry.getKey() as LedgerK.Users] =
                ledgerTableOperation.entry.getValue() as LedgerV.Users
            is LedgerTableEntry.Sessions -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.Sessions, LedgerV.Sessions>)[ledgerTableOperation.entry.getKey() as LedgerK.Sessions] =
                ledgerTableOperation.entry.getValue() as LedgerV.Sessions
            is LedgerTableEntry.OrderRecords -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.OrderRecords, LedgerV.OrderRecords>)[ledgerTableOperation.entry.getKey() as LedgerK.OrderRecords] =
                ledgerTableOperation.entry.getValue() as LedgerV.OrderRecords
            is LedgerTableEntry.PositionRecords -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.PositionRecords, LedgerV.PositionRecords>)[ledgerTableOperation.entry.getKey() as LedgerK.PositionRecords] =
                ledgerTableOperation.entry.getValue() as LedgerV.PositionRecords
            is LedgerTableEntry.NotificationRules -> (getTable(ledgerTableOperation.entry) as MutableMap<LedgerK.NotificationRules, LedgerV.NotificationRules>)[ledgerTableOperation.entry.getKey() as LedgerK.NotificationRules] =
                ledgerTableOperation.entry.getValue() as LedgerV.NotificationRules
        }
    }

    fun getTable(entry: LedgerTableEntry) = when (entry) {
        is LedgerTableEntry.Tickers -> tickers
        is LedgerTableEntry.Users -> users
        is LedgerTableEntry.Sessions -> sessions
        is LedgerTableEntry.OrderRecords -> orderRecords
        is LedgerTableEntry.PositionRecords -> positionRecords
        is LedgerTableEntry.NotificationRules -> notificationRules
    }
}
