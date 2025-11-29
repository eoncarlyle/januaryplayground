package ledger

import arrow.core.raise.either
import arrow.fx.stm.TVar
import arrow.fx.stm.atomically
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.AppKafkaProducer
import com.iainschmitt.januaryplaygroundbackend.shared.kafka.deserializeEither
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

class LedgerState(
    val tickers: MutableMap<LedgerK.Tickers, LedgerV.Tickers> = mutableMapOf(),
    val users: MutableMap<LedgerK.Users, LedgerV.Users> = mutableMapOf(),
    val sessions: MutableMap<LedgerK.Sessions, LedgerV.Sessions> = mutableMapOf(),
    val orderRecords: MutableMap<LedgerK.OrderRecords, LedgerV.OrderRecords> = mutableMapOf(),
    val positionRecords: MutableMap<LedgerK.PositionRecords, LedgerV.PositionRecords> = mutableMapOf(),
    val notificationRules: MutableMap<LedgerK.NotificationRules, LedgerV.NotificationRules> = mutableMapOf()
)

class Ledger(
    var producer: AppKafkaProducer,
    val txLedgerTopic: String,
    initialLedgerState: LedgerState,
    val logger: Logger
) {
    private var nextOrderId = 0L
    private val ledgerRequestQueue = LinkedBlockingQueue<LedgerRequestEntry<*>>()

    // TODO talk about this design pattern this is great - solves the fact that you want to capture the type T and not
    // requiring casts and `Any`
    private var ledgerState = runBlocking { TVar.new(initialLedgerState) }

    fun <T> submitWithHandle(
        ledgerRequestsFactory: (ledgerState: LedgerState) -> List<LedgerTableOperation>,
        getResultFromFinalState: (ledgerState: LedgerState) -> T
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        ledgerRequestQueue.put(LedgerRequestEntry(future) {
            runBlocking {
                atomically {
                    catch({
                        val initialLedgerState = ledgerState.read()
                        val ledgerRequests = ledgerRequestsFactory(initialLedgerState)
                        ledgerState.write(apply(initialLedgerState, ledgerRequests))
                        producer.sendSync(
                            txLedgerTopic,
                            null,
                            Json.encodeToString(serializer<List<LedgerTableOperation>>(), ledgerRequests)
                        )
                        val finalLedgerState = ledgerState.read()
                        getResultFromFinalState(
                            finalLedgerState,
                        )
                    }) {
                        // This just completes exceptionally in the future
                        throw RuntimeException("Error during : $it")
                    }
                }
            }
        })
        return future
    }
    fun <T> submit(
        ledgerRequests: List<LedgerTableOperation>,
        getResultFromFinalState: (ledgerState: LedgerState) -> T
    ): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        ledgerRequestQueue.put(LedgerRequestEntry(future) {
            runBlocking {
                atomically {
                    catch({
                        ledgerState.write(apply(ledgerState.read(), ledgerRequests))
                        producer.sendSync(
                            txLedgerTopic,
                            null,
                            Json.encodeToString(serializer<List<LedgerTableOperation>>(), ledgerRequests)
                        )
                        getResultFromFinalState(
                            ledgerState.read()
                        )
                    }) {
                        // This just completes exceptionally in the future
                        throw RuntimeException("Error during : $it")
                    }
                }
            }
        })
        return future
    }

    // Need dedicated thread on this
    fun processNext() {
        ledgerRequestQueue.take().execute()
    }

    fun apply(ledgerState: LedgerState, ledgerTableOperations: List<LedgerTableOperation>): LedgerState {
        ledgerTableOperations.forEach { apply(ledgerState, it) }
        return ledgerState
    }

    private fun apply(ledgerState: LedgerState, ledgerTableOperation: LedgerTableOperation): LedgerState {
        when (ledgerTableOperation.type) {
            is LedgerTableOperationType.Create -> {
                when (ledgerTableOperation.entry) {
                    is LedgerTableEntry.OrderRecords -> {
                        upsert(
                            ledgerState,
                            LedgerTableOperation(
                                LedgerTableEntry.OrderRecords(
                                    LedgerK.OrderRecords(nextOrderId++),
                                    ledgerTableOperation.entry.getValue() as LedgerV.OrderRecords
                                ), LedgerTableOperationType.Create
                            )
                        )
                    }
                    else -> upsert(ledgerState, ledgerTableOperation)
                }
            }

            is LedgerTableOperationType.Update -> {
                if ((getTable(ledgerState, ledgerTableOperation.entry)[ledgerTableOperation.entry.getKey()]) != null) {
                    upsert(ledgerState, ledgerTableOperation)
                }
            }

            is LedgerTableOperationType.Delete -> {
                getTable(ledgerState, ledgerTableOperation.entry).remove(ledgerTableOperation.entry.getKey())
            }
        }
        return ledgerState
    }

    private fun upsert(ledgerState: LedgerState, ledgerTableOperation: LedgerTableOperation) {
        //This is absolutely heinous and yes I inlined things that I didn't need to because I could not bear this...
        //...function taking any more lines
        when (ledgerTableOperation.entry) {
            is LedgerTableEntry.Tickers -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.Tickers, LedgerV.Tickers>)[ledgerTableOperation.entry.getKey() as LedgerK.Tickers] =
                ledgerTableOperation.entry.getValue() as LedgerV.Tickers

            is LedgerTableEntry.Users -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.Users, LedgerV.Users>)[ledgerTableOperation.entry.getKey() as LedgerK.Users] =
                ledgerTableOperation.entry.getValue() as LedgerV.Users

            is LedgerTableEntry.Sessions -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.Sessions, LedgerV.Sessions>)[ledgerTableOperation.entry.getKey() as LedgerK.Sessions] =
                ledgerTableOperation.entry.getValue() as LedgerV.Sessions

            is LedgerTableEntry.OrderRecords -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.OrderRecords, LedgerV.OrderRecords>)[ledgerTableOperation.entry.getKey() as LedgerK.OrderRecords] =
                ledgerTableOperation.entry.getValue() as LedgerV.OrderRecords

            is LedgerTableEntry.PositionRecords -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.PositionRecords, LedgerV.PositionRecords>)[ledgerTableOperation.entry.getKey() as LedgerK.PositionRecords] =
                ledgerTableOperation.entry.getValue() as LedgerV.PositionRecords

            is LedgerTableEntry.NotificationRules -> (getTable(
                ledgerState,
                ledgerTableOperation.entry
            ) as MutableMap<LedgerK.NotificationRules, LedgerV.NotificationRules>)[ledgerTableOperation.entry.getKey() as LedgerK.NotificationRules] =
                ledgerTableOperation.entry.getValue() as LedgerV.NotificationRules
        }
    }

    fun getTable(ledgerState: LedgerState, entry: LedgerTableEntry) = when (entry) {
        is LedgerTableEntry.Tickers -> ledgerState.tickers
        is LedgerTableEntry.Users -> ledgerState.users
        is LedgerTableEntry.Sessions -> ledgerState.sessions
        is LedgerTableEntry.OrderRecords -> ledgerState.orderRecords
        is LedgerTableEntry.PositionRecords -> ledgerState.positionRecords
        is LedgerTableEntry.NotificationRules -> ledgerState.notificationRules
    }

    fun getLedgerState() = runBlocking { atomically { ledgerState.read() } }

    fun messageProcessor(record: ConsumerRecord<String, String>) {
        either {
            val dto = record.value().deserializeEither<List<LedgerTableOperation>>().bind()
            runBlocking {
                atomically {
                    apply(ledgerState.read(), dto)
                }
            }
        }
    }
}
