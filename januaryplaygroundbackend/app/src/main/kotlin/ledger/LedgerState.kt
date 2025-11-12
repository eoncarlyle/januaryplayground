package ledger

import arrow.core.raise.either
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import org.apache.kafka.clients.consumer.ConsumerRecord
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue


class LedgerState(
    val tickers: MutableMap<LedgerK.Tickers, LedgerV.Tickers> = mutableMapOf(),
    val users: MutableMap<LedgerK.Users, LedgerV.Users> = mutableMapOf(),
    val sessions: MutableMap<LedgerK.Sessions, LedgerV.Sessions> = mutableMapOf(),
    val orderRecords: MutableMap<LedgerK.OrderRecords, LedgerV.OrderRecords> = mutableMapOf(),
    val positionRecords: MutableMap<LedgerK.PositionRecords, LedgerV.PositionRecords> = mutableMapOf(),
    val notificationRules: MutableMap<LedgerK.NotificationRules, LedgerV.NotificationRules> = mutableMapOf()
) {
    private var nextOrderId = 0L
    private val ledgerRequestQueue = LinkedBlockingQueue<LedgerRequestEntry<*>>()
    // TODO talk about this design pattern this is great - solves the fact that you want to capture the type T and not
    // requiring casts and `Any`

    fun <T> submit(block: (ledgerState: LedgerState) -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        ledgerRequestQueue.put(LedgerRequestEntry(future) { block(this) })
        return future
    }

    fun processNext() {
        ledgerRequestQueue.take().execute()
    }

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

    fun messageProcessor(record: ConsumerRecord<String, String>) {
        either {
            val dto = record.value().deserializeEither<List<LedgerTableOperation>>().bind()
            apply(dto)
        }
    }
}
