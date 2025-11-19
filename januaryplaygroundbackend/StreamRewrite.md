# Stream Rewrite


# 2025.11.13

Horrible! Glad it is gone
```kotlin
class LedgerRequestEntry<T>(
    private val future: CompletableFuture<T>,
    private val cleanup: () -> Unit,
    private val stateChangeAndResult: () -> T,
) {
    fun execute() {
        try {
            future.complete(stateChangeAndResult())
        } catch (e: Exception) {
            cleanup()
            future.completeExceptionally(e)
        }
    }
}
```

# 2025.11.12
STM is great and probably the right way to do this. It would require bring coroutines into the ledger operations and I
don't feel great about that. If a simple 

```kotlin
fun STM.transfer(bal: TVar<MutableMap<String, Int>>): Unit {
    bal.write(mutableMapOf("a" to 2))
    throw RuntimeException()
    bal.write(mutableMapOf("a" to 3))
}


suspend fun main() {
    val bal = TVar.new(mutableMapOf("a" to 1))
    println("Balance: ${bal.unsafeRead()}")
    atomically {
        catch({ transfer(bal) }) { e ->
            println("Caught exception: ${e.message}")
        }
    }
    println("Balance: ${bal.unsafeRead()}")
}
```


# 2025.11.11
This is something close to what we need - but providing a means to modify state directly is not what we want. And it 
isn't transactional either (the prior more important than the latter) but the analogy is `DatabaseHelper`.

```kotlin
private val ledgerRequestQueue = LinkedBlockingQueue<LedgerRequestEntry<*>>()

fun <T> submit(block: (ledgerState: LedgerState) -> T): CompletableFuture<T> {
    val future = CompletableFuture<T>()
    ledgerRequestQueue.put(LedgerRequestEntry(future) { block(this) })
    return future
}
```

# 2025.11.10
The ledger requests shouldn't be on seperate Kafka topic with how I have things configured. The reason that I am using
Kafka is just to have a ledger log, but there isn't a reason to persist the requests. This still allows for Kafka 
transactions, as producer-only operations can be transactional even if consumer-only actions are not.

Was briefly unsure how to tie the `LedgerRequestQueue` to Kafka - we need a construct that can handle both the 
intermediate result and the state of the ledger. A function needs to be provided to the queue that will

```haskel
modify :: (s -> s) -> m ()
get :: m s
```

# 2025.11.09
The way that responses will be handled is that the services will place a `Pair<CompleteableFuture, Operation>` onto a 
LinkedBlockingQueue. The Kafka transaction producing thread will complete the future after the Kafka transaction takes
place. I don't love this - it would be nice to have some two-phased commit instead - but this is probably good enough.

Each ledger record should contain a list of CRUD operations on a table, and now I have to figure out what the ledger 
looks like for each of the table records, which I have done in `LedgerTableEntry`. Neccessarily, there are three 
operations on the ledger
- Adding a ledger entry, which requires a key and a value (integer saftey important on some of these)
- Removing a ledger entry, which just requires a key
- Updating a ledger entry, which requires both a key and a value

Now certainly we can find ourselves in a place where all ledger table entries that satisfy certain criteria are 
requested, but that can be handled in the ledger class itself.

# 2025.11.08

Starting off, it makes a lot more sense to provide a properties file rather than compile a JAR with one. So that gives
us the following `applications.properties`, used with the arguments:
`/Users/iain/code/januraryplayground/resources/application.properties insecure`

```properties
database=/Users/iain/code/januraryplayground/app.sqlite
bootstrap.servers=santa-cruz-kafka-0.iainschmitt.com:9093
security.protocol=SSL
ssl.keystore.type=JKS
ssl.keystore.location=/Users/iain/code/januraryplayground/januaryplaygroundbackend/app/src/main/resources/client/client.keystore.jks
ssl.keystore.password=*****
ssl.key.password=*****
ssl.truststore.type=JKS
ssl.truststore.location=/Users/iain/code/januraryplayground/januaryplaygroundbackend/app/src/main/resources/client/client.truststore.jks
ssl.truststore.password=*****
ssl.protocol=TLSv1.2
ssl.enabled.protocols=TLSv1.2
ssl.endpoint.identification.algorithm=
```

There should only be two topics: `tx-requests` and `tx-ledger`. The HTTP request accepting thread will emit onto the
request thread and a consuming thread will, in a Kafka transaction, read from `requests` . I hate having enviornment
specific topics in the same cluster, but I just won't spin up a seperate broker. The `notification_rules`,
`order_records`, `position_records`, `session`, `ticker` , and `user` will be calculated on startup by an ad-hoc
consumer going down the length of `tx-ledger`. Because there will only ever be one thread producing onto `tx-ledger`,
the log-end offset when the backend starts will be the same offset as when the ad-hoc consumer finishes building the
table equivalent data structures.

```kotlin
        val endOffsets = mutableMapOf<Int, Long>()
val currentOffsets = mutableMapOf<Int, Long>()
var isInitialized = false

while (true) {
    val records = consumer.poll(Duration.ofMillis(1000))

    if (!isInitialized && consumer.assignment().isNotEmpty()) {
        val endOffsetsMap = consumer.endOffsets(consumer.assignment())
        endOffsetsMap.forEach { (partition, offset) ->
            endOffsets[partition.partition()] = offset
        }
        isInitialized = true
    }

    if (records.isEmpty) {
        if (isInitialized && currentOffsets.isNotEmpty()) {
            val allPartitionsComplete = endOffsets.all { (partition, endOffset) ->
                val currentOffset = currentOffsets[partition] ?: 0
                currentOffset >= endOffset
            }

            if (allPartitionsComplete) {
                break
            }
        }
        continue
    }

    records.forEach { record ->
        currentOffsets[record.partition()] = record.offset() + 1
    }

    consumer.commitSync()
}
```
