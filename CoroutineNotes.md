# Coroutine Notes
While you can run the main function of a Kotlin coroutine as a coroutine with `suspend`, generally  the way that new coroutines are created (especially if you cannot so blantaly change the 'colour' of the function as would be needed in existing functions), is through coroutine builders. Said courtine buildiers include `runBlocking` to bridge between blocking code and coroutines, `launch` for coroutines that do not return values, and `async` for returning values asyncronously. Note that coroutines are not part of the standard library, but rather are part of the `kotlinx.coroutines` library.

The following only takes 400 milliseconds, as `myFirstDeferred` and `mySecondDeferred` can `delay` simultaneously.

```kotlin
suspend fun slowlyAddNumbers(a: Int, b: Int): Int {
    log("Waiting a bit before calculating $a + $b")
    delay(100.milliseconds * a)
    return a + b
}


fun main() = runBlocking {
    log("Starting the async computation")
    val myFirstDeferred = async { slowlyAddNumbers(2, 2) }
    val mySecondDeferred = async { slowlyAddNumbers(4, 4) }
    log("Waiting for the deferred value to be available")
    log("The first result: ${myFirstDeferred.await()}")
    log("The second result: ${mySecondDeferred.await()}")
}
```

The example below establishes the intuitive parent/child relationship, but this is not what should be done if all that is neccesary is to establish parentage. Rather `coroutineScope` should be used instead

```kotlin
fun main() {
    runBlocking {
        launch {
            delay(1.seconds)
            launch {
                delay(250.milliseconds)
                log("Grandchild done")
            }
            log("Child 1 done!")
        }
        launch {
            delay(500.milliseconds)
            log("Child 2 done!")
        }
        log("Parent done!")
    }
}
```

`CoroutineScope` and `couroutineScope` have an unfortunate capitalisation pattern.
> - You use `coroutineScope` for the concurrent decomposition of work. You launch a number of coroutines, and wait for all of them to complete, potentially computing some kind of result. Because `coroutineScope` waits for all of its children to complete, it is a suspending function.
> - You use `CoroutineScope` to create a scope that associates coroutines with the life cycle of a class. It creates the scope, but doesn’t wait for any further operations, so it returns quickly. It returns you a reference to that coroutine scope, so you can later cancel it.
> In practice, you’ll see many more uses of the suspending coroutineScope than the CoroutineScope constructor function.

Avoid using the global coroutine scope:
> "In general application code, cases where you should choose GlobalScope are extremely rare (one such example are top-level background processes that must stay active for the whole lifetime of an application). You are usually better served finding a more appropriate scope to start your coroutines: either via the coroutine builders or via the coroutineScope function."

Cancellation can be triggered as follows

```kotlin
fun main() {
    runBlocking {
        val deferred = async {
            log("I'm async")
            delay(1000.milliseconds)
            log("I'm done!")
        }
        delay(200.milliseconds)
        launchedJob.cancel()
        asyncDeferred.cancel()
    }
}

```
Can use `withTimeout` and `withTimeoutOrNull` for timed autocancelling. Coroutines throw a `CancellationExcetion` to accomplish cancelation, but a) this can only be thrown during a suspsend and B) if the exception is caught, the coroutine can't be cancelled. Coroutine cancellation/opeartion is cooperative, adn `isActive`, `ensureActive`, and `yield()` can be used accordingly.
