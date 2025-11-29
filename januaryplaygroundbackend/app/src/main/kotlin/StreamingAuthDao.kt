import arrow.core.Either
import arrow.core.Option
import arrow.core.none
import com.iainschmitt.januaryplaygroundbackend.shared.*
import ledger.Ledger
import ledger.LedgerK
import ledger.LedgerTableOperation
import ledger.LedgerTableOperationType
import ledger.userLedgerOperation
import java.sql.Connection

class StreamingAuthDao(
    private val db: DatabaseHelper,
    private val ledger: Ledger
) {
    fun createUser(email: String, passwordHash: String, accountType: AccountType = AccountType.STANDARD,
                   orchestratedBy: String? = null) =
        Either.catch {
            val user = ledger.submit(
                listOf(
                    userLedgerOperation(LedgerTableOperationType.Create, email, passwordHash, 0, accountType, orchestratedBy)
                )
            ) { state -> state.users[LedgerK.Users(email)] }

            user.get()
            Unit
        }

    fun getMaybePasswordHash(email: String) = Option.fromNullable(ledger.getLedgerState().users[LedgerK.Users(email)])


    fun createOrchestratedUser(dto: OrchestratedCredentialsDto, passwordHash: String, orchestratorEmail: String) = Either.catch {
        // The solution is sending a partial ledger record where an updating funciton is passed of the value type
        listOf(
            userLedgerOperation(LedgerTableOperationType.Update, orchestratorEmail, )
        )
    }

    fun _createOrchestratedUser(dto: OrchestratedCredentialsDto, passwordHash: String, orchestratorEmail: String) =
        Either.catch {
            db.query { conn ->
                conn.prepareStatement(
                    "update user set balance = balance - ? where email = ?"
                ).use { stmt ->
                    stmt.setInt(1, dto.initialCreditBalance)
                    stmt.setString(2, orchestratorEmail)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    "insert into user (email, password_hash, balance, orchestrated_by) values (?, ?, ?, ?)"
                ).use { stmt ->
                    stmt.setString(1, dto.userEmail)
                    stmt.setString(2, passwordHash)
                    stmt.setInt(3, dto.initialCreditBalance)
                    stmt.setString(4, orchestratorEmail)
                    stmt.executeUpdate()
                }
            }
        }

    fun liquidateOrchestratedUser(dto: LiquidateOrchestratedUserDto, auth: Pair<String, Long>, targetUserBalance: Int) =
        Either.catch {
            db.query { conn ->
                conn.prepareStatement(
                    """
                    update user set balance = balance - ? where email = ? and orchestrated_by = ?
                """
                ).use { stmt ->
                    stmt.setInt(1, targetUserBalance)
                    stmt.setString(2, dto.targetUserEmail)
                    stmt.setString(3, dto.orchestratorEmail)
                    stmt.executeUpdate()
                }
                conn.prepareStatement(
                    """
                    update user set balance = balance + ? where email = ?
                """
                ).use { stmt ->
                    stmt.setInt(1, targetUserBalance)
                    stmt.setString(2, auth.first)
                    stmt.executeUpdate()
                }

                liquidateSingleUserOrchestratedPositions(conn, dto.orchestratorEmail, dto.targetUserEmail)
            }
        }

    private fun liquidateSingleUserOrchestratedPositions(
        conn: Connection,
        orchestratorEmail: String,
        userEmail: String
    ) {
        val referenceTime = System.currentTimeMillis()

        // Short orders: would have to be mindful about consolidation
        val orchestratedUsersLongPositions = HashMap<String, Int>()
        conn.prepareStatement(
            """
                select ticker, sum(size)
                    from user u
                    left join position_records p on u.email = p.user
                    where orchestrated_by = ? and u.email = ? and p.position_type = ?
                    group by ticker;
            """
        ).use { stmt ->
            stmt.setString(1, orchestratorEmail)
            stmt.setString(2, userEmail)
            stmt.setInt(3, PositionType.LONG.ordinal)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    orchestratedUsersLongPositions[rs.getString(1)] = rs.getInt(2)
                }
            }
        }

        orchestratedUsersLongPositions.forEach { (ticker, longPositions) ->
            conn.prepareStatement(
                """
                    delete from position_records
                        where ticker = ? 
                        and user = ?
                        and position_type = ?
                    """
            ).use { stmt ->
                stmt.setString(1, ticker)
                stmt.setString(2, userEmail) //Inverting these: nasty bug
                stmt.setInt(3, PositionType.LONG.ordinal)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                    insert into position_records (user, ticker, position_type, size, received_tick) values (?, ?, ?, ?, ?)
                        on conflict (user, ticker, position_type)
                        do update set size = size + excluded.size, received_tick = excluded.received_tick
                    """
            ).use { stmt ->
                stmt.setString(1, orchestratorEmail)
                stmt.setString(2, ticker)
                stmt.setInt(3, PositionType.LONG.ordinal)
                stmt.setInt(4, longPositions)
                stmt.setLong(5, referenceTime)
                stmt.executeUpdate()
            }
        }
    }

    fun liquidateAllOrchestratedUsers(orchestratedUsers: List<Pair<String, Int>>, orchestratorEmail: String) =
        Either.catch {
            val totalBalance = orchestratedUsers.sumOf { it.second }

            db.query { conn ->
                conn.prepareStatement(
                    """
                                update user set balance = 0 where orchestrated_by = ?
                            """
                ).use { stmt ->
                    stmt.setString(1, orchestratorEmail)
                    stmt.executeUpdate()
                }

                conn.prepareStatement("update user set balance = balance + ? where email = ?").use { stmt ->
                    stmt.setInt(1, totalBalance)
                    stmt.setString(2, orchestratorEmail)
                    stmt.executeUpdate()
                }

                liquidateAllOrchestratedPositions(conn, orchestratorEmail, orchestratedUsers)
            }
        }

    private fun liquidateAllOrchestratedPositions(
        conn: Connection,
        orchestratorEmail: String,
        orchestratedUsers: List<Pair<String, Int>>
    ) {
        val referenceTime = System.currentTimeMillis()

        // Short orders: would have to be mindful about consolidation
        val orchestratedUsersLongPositions = HashMap<String, Int>()
        conn.prepareStatement(
            """
                select ticker, sum(size)
                    from user u
                    left join position_records p on u.email = p.user
                    where orchestrated_by = ? and p.position_type = ?
                    group by ticker;
            """
        ).use { stmt ->
            stmt.setString(1, orchestratorEmail)
            stmt.setInt(2, PositionType.LONG.ordinal)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    orchestratedUsersLongPositions[rs.getString(1)] = rs.getInt(2)
                }
            }
        }

        val orchestratedUserSqlList =
            orchestratedUsers.map { it.first }.joinToString(prefix = "(", postfix = ")") { "?" }

        orchestratedUsersLongPositions.forEach { (ticker, longPositions) ->
            conn.prepareStatement(
                """
                    delete from position_records
                        where ticker = ? 
                        and user in $orchestratedUserSqlList
                        and position_type = ?
                """
            ).use { stmt ->
                stmt.setString(1, ticker)
                stmt.setInt(2, PositionType.LONG.ordinal)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                """
                    insert into position_records (user, ticker, position_type, size, received_tick) values (?, ?, ?, ?, ?)
                        on conflict (user, ticker, position_type)
                        do update set size = size + excluded.size, received_tick = excluded.received_tick
                        """
            ).use { stmt ->
                stmt.setString(1, orchestratorEmail)
                stmt.setString(2, ticker)
                stmt.setInt(3, PositionType.LONG.ordinal)
                stmt.setInt(4, longPositions)
                stmt.setLong(5, referenceTime)
                stmt.executeUpdate()
            }
        }
    }

    fun getOrchestratedUsersWithBalance(orchestratorEmail: String) = Either.catch {
        db.query { conn ->
            conn.prepareStatement("select email, balance from user where orchestrated_by = ?").use { stmt ->
                stmt.setString(1, orchestratorEmail)
                stmt.executeQuery().use { rs ->
                    val users = mutableListOf<Pair<String, Int>>()
                    while (rs.next()) {
                        users.add(rs.getString("email") to rs.getInt("balance"))
                    }
                    users
                }
            }
        }
    }

    fun transferCredits(dto: CreditTransferDto, auth: Pair<String, Long>) = Either.catch {
        db.query { conn ->
            conn.prepareStatement(
                "update user set balance = balance - ? where email = ?"
            ).use { stmt ->
                stmt.setInt(1, dto.creditAmount)
                stmt.setString(2, auth.first)
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                "update user set balance = balance + ? where email = ?"
            ).use { stmt ->
                stmt.setInt(1, dto.creditAmount)
                stmt.setString(2, dto.targetUserEmail)
                stmt.executeUpdate()
            }
        }
    }

    fun deleteToken(token: String) = db.query { conn ->
        conn.prepareStatement("delete from session where token = ?").use { stmt ->
            stmt.setString(1, token)
            stmt.executeUpdate()
        }
    }

    fun getAuthFromToken(token: String) = db.query { conn ->
        conn.prepareStatement("select email, expire_timestamp from session where token = ?")
            .use { stmt ->
                stmt.setString(1, token)
                stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getLong(2)) else null }
            }
    }

    fun emailPresent(email: String) = db.query { conn ->
        conn.prepareStatement("select email from user where email = ?").use { stmt ->
            stmt.setString(1, email)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    fun isOrchestrator(email: String) = db.query { conn ->
        conn.prepareStatement("select email from user where email = ? and type = ${AccountType.ORCHESTRATOR.ordinal}")
            .use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
    }

    fun isOrchestratedBy(targetUserEmail: String, orchestratorEmail: String) = db.query { conn ->
        conn.prepareStatement("select email from user where email = ? and orchestrated_by = ?").use { stmt ->
            stmt.setString(1, targetUserEmail)
            stmt.setString(2, orchestratorEmail)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    fun hasAtLeastAsManyCredits(email: String, credits: Int) = db.query { conn ->
        conn.prepareStatement("select email from user where email = ? and balance >= ?").use { stmt ->
            stmt.setString(1, email)
            stmt.setInt(2, credits)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }

    fun getBalance(email: String) = db.query { conn ->
        conn.prepareStatement("select balance from user where email = ?").use { stmt ->
            stmt.setString(1, email)
            stmt.executeQuery().use { rs -> if (rs.next()) Option.fromNullable(rs.getInt(1)) else none() }
        }
    }

    fun insertSession(token: String, expireTimestamp: Long, email: String) = db.query { conn ->
        conn.prepareStatement("insert into session (token, expire_timestamp, email) values (?, ?, ?)")
            .use { stmt ->
                stmt.setString(1, token)
                stmt.setLong(2, expireTimestamp)
                stmt.setString(3, email)
                stmt.executeUpdate()
                Unit
            }
    }

    fun removeExistingSessions(email: String) = db.query { conn ->
        val sessionExists = conn.prepareStatement("select * from session where email = ?").use { stmt ->
            stmt.setString(1, email)
            stmt.executeQuery().use { rs -> rs.next() }
        }

        if (sessionExists) {
            conn.prepareStatement("delete from session where email = ?").use { stmt ->
                stmt.setString(1, email)
                stmt.executeUpdate()
            }
        }
    }

}
