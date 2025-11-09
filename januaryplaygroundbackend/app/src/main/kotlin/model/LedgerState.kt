package model

import com.iainschmitt.januaryplaygroundbackend.shared.*
import kotlinx.serialization.Serializable


data class LedgerTickerK(
    val open: Boolean
)

data class LedgerTickerV(
    val open: Boolean
)

data class LedgerUserK(
    val email: String
)

data class LedgerUserV(
    val passwordHash: String,
    val balance: Int,
    val type: AccountType,
    val orchestratedBy: String?
)

data class LedgerSessionK(
    val token: String
)

data class LedgerSessionV(
    val expireTimestamp: Long,
    val email: String
)

data class LedgerOrderRecordsK(
    val id: Long //Safe ID creation will be important
)

data class LedgerOrderRecordsV(
    val userEmail: String,
    val ticker: Ticker,
    val tradeType: TradeType,
    val size: Int,
    val price: Int,
    val orderType: OrderType,
    val filledTick: Long,
    val receivedTick: Long
)

data class LedgerPositionRecordsK(
    val userEmail: String,
    val ticker: Ticker,
    val positionType: PositionType,
)

data class LedgerPositionRecordsV(
    val size: Int,
    val receivedTick: Int
)

data class LedgerNotificationRuleK(
    val userEmail: String
)

data class LedgerNotificationRuleV(
    val category: NotificationCategory,
    val operation: NotificationOperation,
    val timestamp: Long
)

@Serializable
sealed class LedgerTableEntry<K, V> {
    abstract val key: K
    abstract val value: V

    data class LTicker(override val key: LedgerTickerK, override val value: LedgerTickerV) :
        LedgerTableEntry<LedgerTickerK, LedgerTickerV>()

    data class LUser(override val key: LedgerUserK, override val value: LedgerUserV) :
        LedgerTableEntry<LedgerUserK, LedgerUserV>()

    data class LSession(override val key: LedgerSessionK, override val value: LedgerSessionV) :
        LedgerTableEntry<LedgerSessionK, LedgerSessionV>()

    data class LOrderRecords(override val key: LedgerOrderRecordsK, override val value: LedgerOrderRecordsV) :
        LedgerTableEntry<LedgerOrderRecordsK, LedgerOrderRecordsV>()

    data class LPositionRecords(override val key: LedgerPositionRecordsK, override val value: LedgerPositionRecordsV) :
        LedgerTableEntry<LedgerPositionRecordsK, LedgerPositionRecordsV>()

    data class LNotificationRule(
        override val key: LedgerNotificationRuleK,
        override val value: LedgerNotificationRuleV
    ) :
        LedgerTableEntry<LedgerNotificationRuleK, LedgerNotificationRuleV>()
}