import java.sql.Connection
import java.sql.DriverManager

open class DatabaseHelper(private val dbPath: String) {
    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
    private val isolationLevel = Connection.TRANSACTION_SERIALIZABLE

    open fun <T> query(
        block: (Connection) -> T
    ): T {
        connect().use { conn ->
            try {
                conn.autoCommit = false
                conn.transactionIsolation = isolationLevel
                val result = block(conn)
                conn.commit()
                return result
            } catch (e: Exception) {
                throw e
            }
        }
    }
}
