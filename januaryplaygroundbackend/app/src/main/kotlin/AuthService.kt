import arrow.core.Either
import arrow.core.Option
import arrow.core.Some
import arrow.core.getOrElse
import arrow.core.none
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.option
import com.fasterxml.uuid.Generators
import com.iainschmitt.januaryplaygroundbackend.shared.*
import io.javalin.http.*
import io.javalin.websocket.WsConnectContext
import io.javalin.websocket.WsContext
import java.util.concurrent.Semaphore
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.Logger
import java.time.Duration
import java.time.Instant
import kotlin.collections.mapOf

// Should break out the queries here into AuthDao
class AuthService(
    private val db: DatabaseHelper,
    private val secure: Boolean,
    private val wsUserMap: WsUserMap,
    private val logger: Logger
) {

    private val session = "session"
    private val email = "email"
    private val expireTime = "expireTime"

    fun signUp(ctx: Context) {
        parseCtxBodyMiddleware<CredentialsDto>(ctx) { dto ->
            either {
                val passwordHash = BCrypt.hashpw(dto.password, BCrypt.gensalt())
                ensure(!emailPresent(dto.email)) {raise(400 to "Account with email `${dto.email}` already exists") }

                Either.catch {
                    db.query { conn ->
                        conn.prepareStatement(
                            "insert into user (email, password_hash) values (?, ?)"
                        )
                            .use { stmt ->
                                stmt.setString(1, dto.email)
                                stmt.setString(2, passwordHash)
                                stmt.executeUpdate()
                            }
                    }
                }.onLeft { raise(500 to "Internal error") }
                val session = createSession(dto.email)
                ctx.cookie(session.first)
                ctx.status(200)
                ctx.json(mapOf(email to dto.email, expireTime to session.second.toString()))
            }.onLeft { throwable ->
                ctx.status(500)
                ctx.json(mapOf("message" to throwable))
            }
        }
    }


    fun logIn(ctx: Context) {
        parseCtxBodyMiddleware<CredentialsDto>(ctx) {dto ->
            val maybePasswordHash =
                db.query { conn ->
                    conn.prepareStatement("select password_hash from user where email = ?").use { stmt
                        ->
                        stmt.setString(1, dto.email)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) Option.fromNullable(rs.getString("password_hash")) else none()
                        }
                    }
                }

            maybePasswordHash.filter { passwordHash -> BCrypt.checkpw(dto.password, passwordHash) }.onSome {
                val session = createSession(dto.email)
                ctx.cookie(session.first)
                ctx.status(200)
                ctx.json(mapOf(email to dto.email, expireTime to session.second.toString()))
            }.onNone {
                ctx.status(404)
                ctx.json(mapOf("message" to "Email or password not found"))
            }
        }
    }

    fun evaluateAuthHandler(ctx: Context) {
        val maybeAuth = evaluateAuth(ctx)
        when (maybeAuth) {
            is Some -> {
                ctx.json(
                    mapOf(
                        "email" to maybeAuth.value.first, "expireTime" to maybeAuth.value.second
                    )
                )
                ctx.status(200)
            }
            else -> {
                ctx.json(mapOf("message" to "Fail"))
                ctx.status(403)
            }
        }
    }

    fun temporarySession(ctx: Context) {
        parseCtxBodyMiddleware<TemporarySessionDto>(ctx) { dto ->
            val maybeAuth = evaluateAuth(ctx)

            maybeAuth.onSome {
                val websocketSession = createSession(dto.email, Duration.ofMinutes(2), false)
                ctx.json(mapOf("token" to websocketSession.first.value))
                ctx.status(201)
            }.onNone {
                ctx.json(mapOf("message" to "Fail"))
                ctx.status(403)
            }
        }
    }

    fun logOut(ctx: Context) {
        val token = ctx.cookie(session)
        val maybeAuth = evaluateAuth(ctx)

        if (token != null && maybeAuth.isSome()) {
            when (deleteToken(token)) {
                true -> {
                    ctx.removeCookie(session); ctx.status(200)
                }

                false -> {
                    ctx.result("Server Error"); ctx.status(500)
                }
            }

        } else {
            ctx.result("User not logged in")
            ctx.status(403)
        }
    }

    fun signUpOrchestrated(ctx: Context, writeSemaphore: Semaphore) {
        writeSemaphore.acquire()
        try {
            val result = either {
                val dto = parseCtxBody<OrchestratedCredentialsDto>(ctx).bind()
                val auth = evaluateAuth(ctx).getOrElse { raise(404 to "User authentication failed") }
                ensure(isAdmin(auth.first)) { 403 to "Must be admin to create orchestrated user" }
                ensure(hasAtLeastAsManyCredits(auth.first, dto.initialCreditBalance)) { 403 to "Insufficient Funds" }

                val passwordHash = BCrypt.hashpw(dto.userPassword, BCrypt.gensalt())

                Either.catch {
                    db.query { conn ->
                        conn.prepareStatement(
                            "update user set balance = balance - ? where email = ?"
                        ).use { stmt ->
                            stmt.setInt(1, dto.initialCreditBalance)
                            stmt.setString(2, auth.first)
                            stmt.executeUpdate()
                        }
                        conn.prepareStatement(
                            "insert into user (email, password_hash, orchestrated_by) values (?, ?, ?)"
                        ).use { stmt ->
                            stmt.setString(1, dto.userEmail)
                            stmt.setString(2, passwordHash)
                            stmt.setString(3, auth.first)
                            stmt.executeUpdate()
                        }
                    }
                }.mapLeft { throwable -> 500 to "Internal server error" }.bind()
                201 to "Update successful"
            }
            result.onLeft { error ->
                ctx.status(error.first)
                ctx.json("message" to error.second)
            }.onRight { success -> ctx.status(201) }
        } finally {
            writeSemaphore.release()
        }
    }

    fun transferCredits(ctx: Context, writeSemaphore: Semaphore) {
        writeSemaphore.acquire()
        try {
            parseCtxBodyMiddleware<CreditTransferDto>(ctx) { dto ->
                val result = either {
                    val auth = evaluateAuth(ctx).getOrElse { raise(404 to "User authentication failed") }
                    ensure(hasAtLeastAsManyCredits(auth.first, dto.creditAmount)) { 403 to "Insufficient Funds" }
                    ensure(emailPresent(dto.targetUserEmail)) { 400 to "Target user does not exist" }

                    Either.catch {
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
                    }.mapLeft { throwable -> 500 to "Internal server error" }.bind()
                    201 to "Update successful"
                }

                result.onLeft { error ->
                    ctx.status(error.first)
                    ctx.json("message" to error.second)
                }.onRight { success -> ctx.status(201) }
            }
        } finally {
            writeSemaphore.release()
        }
    }

    fun handleWsConnection(ctx: WsConnectContext) {
        logger.info("Incoming connection")
        wsUserMap.set(ctx, WsUserMapRecord(null, null, false, listOf()))
        ctx.sendAsClass(
            ServerLifecycleMessage(
                WebSocketLifecycleOperation.AUTHENTICATE,
                WebSocketResponseStatus.ACCEPTED,
                null,
                "Connection attempt acknowledged"
            )
        )
    }

    fun handleWsLifecycleMessage(ctx: WsContext, message: ClientLifecycleMessage) {
        logger.info("Incoming auth request")
        val token = message.token
        val email = message.email

        val userAuth = evaluateAuthFromToken(token)
        if (userAuth == null || userAuth.first != email) {
            ctx.closeSession(WebSocketResponseStatus.UNAUTHORIZED.code, "invalid token")
            return
        }

        when (message.operation) {
            WebSocketLifecycleOperation.AUTHENTICATE -> {
                wsUserMap.set(ctx, WsUserMapRecord(token, email, true, message.tickers))
                ctx.sendAsClass(
                    ServerLifecycleMessage(
                        WebSocketLifecycleOperation.AUTHENTICATE,
                        WebSocketResponseStatus.SUCCESS,
                        email,
                        "Authentication success",
                    )
                )
                //TODO handling error cases
                deleteToken(token)
                return
            }

            WebSocketLifecycleOperation.CLOSE -> {
                return handleWsClose(ctx, email)
            }
        }
    }

    fun handleWsClose(ctx: WsContext, email: String?) {
        wsUserMap.remove(ctx)
        ctx.sendAsClass(
            ServerLifecycleMessage(
                WebSocketLifecycleOperation.AUTHENTICATE,
                WebSocketResponseStatus.SUCCESS,
                email,
                "Socket closed"
            )
        )
        return
    }

    private fun deleteToken(token: String): Boolean {
        val edits = db.query { conn ->
            conn.prepareStatement("delete from session where token = ?").use { stmt ->
                stmt.setString(1, token)
                stmt.executeUpdate()
            }
        }

        // TODO logging when edits > 1
        return edits > 0
    }

    private fun evaluateAuthFromToken(token: String): Pair<String, Long>? {
        val pair =
            db.query { conn ->
                conn.prepareStatement("select email, expire_timestamp from session where token = ?")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getLong(2)) else null }
                    }
            }
        return if (pair == null || pair.second < Instant.now().toEpochMilli()) {
            null
        } else {
            pair
        }
    }

    fun evaluateAuth(ctx: Context): Option<Pair<String, Long>> {
        return option {
            val email = Option.fromNullable(ctx.bodyAsClass<Map<String, Any>>()["email"])
                .map { it.toString() }.bind()
            val token = Option.fromNullable(ctx.cookie(session)).bind()
            val maybePair =
                db.query { conn ->
                    conn.prepareStatement("select email, expire_timestamp from session where token = ? and email = ?")
                        .use { stmt ->
                            stmt.setString(1, token)
                            stmt.setString(2, email)
                            stmt.executeQuery()
                                .use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getLong(2)) else null }
                        }
                }
            Option.fromNullable(maybePair).bind()
        }.filter { pair -> pair.second >= Instant.now().toEpochMilli() }
    }

    private fun emailPresent(email: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select email from user where email = ?").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun isAdmin(email: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select email from user where email = ? and is_admin = 1").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    // Assumes already within transaction semaphore!
    private fun hasAtLeastAsManyCredits(email: String, credits: Int): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select email from user where email = ? and balance >= ?").use { stmt ->
                stmt.setString(1, email)
                stmt.setInt(2, credits)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun createSession(
        email: String,
        cookieLifetime: Duration = Duration.ofHours(24),
        isHttpOnly: Boolean = true
    ): Pair<Cookie, Long> {
        val expireTimestamp = Instant.now().plus(cookieLifetime).toEpochMilli()
        // Do not like that I can't specify a timestamp as `maxAge`
        val token = Generators.randomBasedGenerator().generate().toString()
        val cookie =
            Cookie(
                session,
                token,
                maxAge = cookieLifetime.toSeconds().toInt(),
                secure = secure,
                sameSite = if (secure) SameSite.STRICT else SameSite.LAX,
                isHttpOnly = isHttpOnly,
                path = "/"
            )

        try {
            db.query { conn ->
                conn.prepareStatement("insert into session (token, expire_timestamp, email) values (?, ?, ?)")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.setLong(2, expireTimestamp)
                        stmt.setString(3, email)
                        stmt.executeUpdate()
                    }
            }

            return Pair(cookie, expireTimestamp)
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`handleAuth` error", e))
        }
    }

    private fun removeExistingSessions(email: String) {
        try {
            val sessionExists = db.query { conn ->
                conn.prepareStatement("select * from session where email = ?").use { stmt ->
                    stmt.setString(1, email)
                    stmt.executeQuery().use { rs -> rs.next() }
                }
            }

            if (sessionExists) {
                db.query { conn ->
                    conn.prepareStatement("delete from session where email = ?").use { stmt ->
                        stmt.setString(1, email)
                        stmt.executeUpdate()
                    }
                }
            }

        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`clearSession` error", e))
        }
    }

    private fun exceptionMessage(baseMessage: String, e: Exception): String {
        return when (secure) {
            true -> baseMessage
            false -> "${baseMessage}: ${e.message}"
        }
    }

    private class TemporarySessionDto(val email: String)
}
