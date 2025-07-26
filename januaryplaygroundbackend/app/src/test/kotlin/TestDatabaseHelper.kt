import java.sql.Connection
import java.sql.DriverManager

class TestDatabaseHelper(dbPath: String) : DatabaseHelper(dbPath) {
    private val connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")

    override fun <T> query(block: (Connection) -> T): T {
        return try {
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_SERIALIZABLE
            val result = block(connection)
            connection.commit()
            result
        } catch (e: Exception) {
            connection.rollback()
            throw e
        }
    }

    fun close() = connection.close()
}