import kotlinx.coroutines.*
import com.iainschmitt.januaryplaygroundbackend.shared.*

fun main(): Unit = runBlocking {

    val email = "testmm@iainschmitt.com"
    val password = "myTestMmPassword"
    val ticker = "testTicker"

    MarketMaker(email, password, ticker).main()
}