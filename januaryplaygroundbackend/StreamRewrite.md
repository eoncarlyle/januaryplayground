# Stream Rewrite

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