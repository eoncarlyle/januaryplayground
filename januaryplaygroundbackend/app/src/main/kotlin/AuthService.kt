import arrow.core.Either
import arrow.core.Option
import arrow.core.getOrElse
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
    private val authDao = AuthDao( db)
    private val session = "session"
    private val email = "email"
    private val expireTime = "expireTime"

    fun signUp(ctx: Context) {
        parseCtxBodyMiddleware<CredentialsDto>(ctx) { dto ->
            either {
                val passwordHash = BCrypt.hashpw(dto.password, BCrypt.gensalt())
                ensure(!emailPresent(dto.email)) { raise(400 to "Account with email `${dto.email}` already exists") }

                authDao.createUser(dto.email, passwordHash).onLeft { raise(500 to "Internal error") }
                val session = createSession(dto.email)
                ctx.cookie(session.first)
                ctx.status(200)
                ctx.json(mapOf(email to dto.email, expireTime to session.second.toString()))
            }.onLeft { throwable ->
                ctx.status(throwable.first)
                ctx.json(throwable.second)
            }
        }
    }


    fun logIn(ctx: Context) {
        parseCtxBodyMiddleware<CredentialsDto>(ctx) { dto ->
            authDao.getMaybePasswordHash(dto.email)
                .filter { passwordHash -> BCrypt.checkpw(dto.password, passwordHash) }.onSome {
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
        evaluateAuth(ctx).onSome { it ->
            ctx.json(
                mapOf(
                    "email" to it.first, "expireTime" to it.second
                )
            )
            ctx.status(200)
        }.onNone {
            ctx.json(mapOf("message" to "Fail"))
            ctx.status(403)
        }
    }

    fun temporarySession(ctx: Context) {
        parseCtxBodyMiddleware<TemporarySessionDto>(ctx) { dto ->
            evaluateAuth(ctx).onSome {
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

        if (token != null && evaluateAuth(ctx).isSome()) {
            if (deleteToken(token)) {
                ctx.removeCookie(session); ctx.status(201)
            } else {
                ctx.result("Server Error"); ctx.status(500)
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
                val orchestratorEmail = auth.first
                ensure(isOrchestrator(orchestratorEmail)) { 403 to "Must be admin to create orchestrated user" }
                ensure(
                    hasAtLeastAsManyCredits(
                        orchestratorEmail,
                        dto.initialCreditBalance
                    )
                ) { 403 to "Insufficient Funds" }

                val passwordHash = BCrypt.hashpw(dto.userPassword, BCrypt.gensalt())

                authDao.createOrchestratedUser(dto, passwordHash, orchestratorEmail)
                    .mapLeft { 500 to "Internal server error" }.bind()
                201 to "Update successful"
            }
            result.onLeft { error ->
                ctx.status(error.first)
                ctx.json("message" to error.second)
            }.onRight { ctx.status(201) }
        } finally {
            writeSemaphore.release()
        }
    }

    fun liquidateSingleOrchestratedUser(ctx: Context, writeSemaphore: Semaphore) {
        writeSemaphore.acquire()
        try {
            val result = either {
                val dto = parseCtxBody<LiquidateOrchestratedUserDto>(ctx).bind()
                val auth = evaluateAuth(ctx).getOrElse { raise(404 to "User authentication failed") }
                ensure(isOrchestrator(auth.first)) { 403 to "Must be admin to liquidate orchestrated users" }
                ensure(isOrchestratedBy(dto.targetUserEmail, auth.first)) {
                    403 to "Target account not orchestrated by user"
                }

                liquidateSingleOrchestratedUser(dto, auth).bind()
                201 to "Update successful"
            }
            result.onLeft { error ->
                ctx.status(error.first)
                ctx.json("message" to error.second)
            }.onRight { ctx.status(204) }
        } finally {
            writeSemaphore.release()
        }
    }

    private fun liquidateSingleOrchestratedUser(
        dto: LiquidateOrchestratedUserDto,
        auth: Pair<String, Long>
    ): Either<Pair<Int, String>, Unit> {
        val targetUserBalance = getBalance(dto.targetUserEmail).getOrElse { 0 }
        return authDao.liquidateOrchestratedUser(dto, auth, targetUserBalance).mapLeft { throwable ->
            logger.error(throwable.message)
            500 to "Internal server error"
        }
    }

    fun liquidateAllOrchestratedUsers(ctx: Context, writeSemaphore: Semaphore) {
        writeSemaphore.acquire()
        try {
            either {
                val auth = evaluateAuth(ctx).getOrElse { raise(404 to "User authentication failed") }
                val orchestratorEmail = auth.first
                ensure(isOrchestrator(orchestratorEmail)) { 403 to "Must be admin to liquidate orchestrated users" }

                val orchestratedUsers = getOrchestratedUsersWithBalance(orchestratorEmail).bind()
                if (orchestratedUsers.isNotEmpty()) {
                    // Should probably return something different if no results but leaving this for now
                    authDao.liquidateAllOrchestratedUsers(orchestratedUsers, orchestratorEmail)
                        .mapLeft { 500 to "Internal server error" }.bind()
                }
                201 to "Update successful"
            }.fold(
                { error ->
                    ctx.status(error.first)
                    ctx.json("message" to error.second)
                },
                { ctx.status(204) }
            )
        } finally {
            writeSemaphore.release()
        }
    }

    private fun getOrchestratedUsersWithBalance(orchestratorEmail: String): Either<Pair<Int, String>, List<Pair<String, Int>>> {
        return authDao.getOrchestratedUsersWithBalance(orchestratorEmail).mapLeft { 500 to "Internal server error" }
    }

    fun transferCredits(ctx: Context, writeSemaphore: Semaphore, onSuccess: (CreditTransferDto) -> Unit) {
        writeSemaphore.acquire()
        try {
            parseCtxBodyMiddleware<CreditTransferDto>(ctx) { dto ->
                val result = either {
                    val auth = evaluateAuth(ctx).getOrElse { raise(404 to "User authentication failed") }
                    ensure(hasAtLeastAsManyCredits(auth.first, dto.creditAmount)) { 403 to "Insufficient Funds" }
                    ensure(emailPresent(dto.targetUserEmail)) { 400 to "Target user does not exist" }

                    authDao.transferCredits(dto, auth).mapLeft { _ -> 500 to "Internal server error" }.bind()
                    201 to "Update successful"
                    onSuccess(dto)
                }

                result.fold(
                    { error ->
                        ctx.status(error.first)
                        ctx.json("message" to error.second)
                    },
                    { ctx.status(201) }
                )
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
        val edits = authDao.deleteToken(token)
        // TODO logging when edits > 1
        return edits > 0
    }

    private fun evaluateAuthFromToken(token: String): Pair<String, Long>? {
        val maybePair = authDao.getAuthFromToken(token)

        return if (maybePair == null || maybePair.second < Instant.now().toEpochMilli()) {
            null
        } else {
            maybePair
        }
    }

    // TODO While different DTOs need to send the current user email on different keys, this should accept some common interface
    fun evaluateAuth(ctx: Context): Option<Pair<String, Long>> {
        return option {
            val token = Option.fromNullable(ctx.cookie(session)).bind()
            val maybePair = authDao.getAuthFromToken(token)

            Option.fromNullable(maybePair).bind()
        }.filter { pair -> pair.second >= Instant.now().toEpochMilli() }
    }

    private fun emailPresent(email: String) = authDao.emailPresent(email)

    private fun isOrchestrator(email: String) = authDao.isOrchestrator(email)

    private fun isOrchestratedBy(targetUserEmail: String, orchestratorEmail: String) =
        authDao.isOrchestratedBy(targetUserEmail, orchestratorEmail)

    // Assumes already within transaction semaphore!
    private fun hasAtLeastAsManyCredits(email: String, credits: Int) = authDao.hasAtLeastAsManyCredits(email, credits)

    // Assumes already within transaction semaphore!
    private fun getBalance(email: String): Option<Int> = authDao.getBalance(email)

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
            // TODO make this a proper Either
            authDao.insertSession(token, expireTimestamp, email)

            return Pair(cookie, expireTimestamp)
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`handleAuth` error", e))
        }
    }

    private fun removeExistingSessions(email: String) {
        try {
            authDao.removeExistingSessions(email)
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
