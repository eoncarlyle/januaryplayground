import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

// TODO talk about this design pattern this is great - solves the fact that you want to capture the type T and not
// requiring casts and `Any`
private class LedgerRequestEntry<T>(
    val future: CompletableFuture<T>,
    val block: () -> T
) {
    fun execute() {
        try {
            future.complete(block())
        } catch (e: Exception) {
            future.completeExceptionally(e)
        }
    }
}

class LedgerRequestQueue {
    private val queue = LinkedBlockingQueue<LedgerRequestEntry<*>>()

    fun <T> submit(block: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        queue.put(LedgerRequestEntry(future, block))
        return future
    }

    fun processNext() {
        queue.take().execute()
    }
}