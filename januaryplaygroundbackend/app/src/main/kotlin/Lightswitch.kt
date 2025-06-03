import java.util.concurrent.Semaphore

class Lightswitch(
    private val readWriteSemaphore: Semaphore
){
    private var counter = 0
    private var mutexSem = Semaphore(1)

    fun lock() {
        mutexSem.acquire()
        counter++
        if (counter == 1 ) {
            readWriteSemaphore.acquire()
        }
        mutexSem.release()
    }

    fun unlock() {
        mutexSem.acquire()
        counter--
        if (counter == 0) {
            readWriteSemaphore.release()
        }
        mutexSem.release()
    }
}
