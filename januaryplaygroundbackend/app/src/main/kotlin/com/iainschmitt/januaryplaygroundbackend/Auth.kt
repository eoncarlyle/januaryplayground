package com.iainschmitt.januaryplaygroundbackend

import com.fasterxml.uuid.Generators
import io.javalin.http.*
import io.javalin.websocket.WsConnectContext
import io.javalin.websocket.WsContext
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.Logger
import java.time.Duration
import java.time.Instant

class Auth(private val db: DatabaseHelper, private val secure: Boolean, private val wsUserMap: WsUserMap, private val logger: Logger) {

    private val session = "session"
    private val email = "email"
    private val expireTime = "expireTime"

    fun signUpHandler(ctx: Context) {
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash = BCrypt.hashpw(dto.password, BCrypt.gensalt())
        if (emailPresent(dto.email)) {
            // If the start of this lamda was `return lamda@`, then doing `return@lamda` below after
            // setting
            // status and return code would do the exit early stuff
            throw ForbiddenResponse("Account with email `${dto.email}` already exists")
        }

        try {
            db.query { conn ->
                conn.prepareStatement(
                    "insert into test_users (email, password_hash) values (?, ?)"
                )
                    .use { stmt ->
                        stmt.setString(1, dto.email)
                        stmt.setString(2, passwordHash)
                        stmt.executeUpdate()
                    }
            }
            val session = createSession(dto.email)
            ctx.cookie(session.first)
            ctx.json(mapOf(email to dto.email, expireTime to session.second.toString()))
            ctx.status(201)
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`signUpHandler` error", e))
        }
    }

    fun logInHandler(ctx: Context) {
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash: String? =
            db.query { conn ->
                conn.prepareStatement("select password_hash from test_users where email = ?").use { stmt
                    ->
                    stmt.setString(1, dto.email)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("password_hash") else null
                    }
                }
            }

        if (passwordHash != null && BCrypt.checkpw(dto.password, passwordHash)) {
            val session = createSession(dto.email)
            ctx.cookie(session.first)
            ctx.json(mapOf(email to dto.email, expireTime to session.second.toString()))
            ctx.status(200)
        } else {
            throw ForbiddenResponse("email or password not found")
        }
    }

    fun evaluateAuthHandler(ctx: Context) {
        val token = ctx.cookie(session)
        val userAuth = if (token != null) evaluateUserAuth(token) else null
        if (token != null && userAuth != null) {
            ctx.json(
                mapOf(
                    "email" to userAuth.first, "expireTime" to userAuth.second
                )
            )
            ctx.status(200)
        } else {
            ctx.json(mapOf("message" to "Fail"))
            ctx.status(403)
        }
    }

    // Yet only used by the websockets
    fun temporarySessionHttpHandler(ctx: Context) {
        val token = ctx.cookie(session)
        val userAuth = if (token != null) evaluateUserAuth(token) else null
        val dto = ctx.bodyAsClass(TemporarySessionDto::class.java)
        if (token != null && userAuth != null) {
            //val websocketSession = createSession(dto.email, Duration.ofMinutes(1))
            val websocketSession = createSession(dto.email, Duration.ofMinutes(5), false)
            ctx.json(mapOf("token" to websocketSession.first.value))
            ctx.status(201)
        } else {
            ctx.json(mapOf("message" to "Fail"))
            ctx.status(403)
        }
    }

    fun logOut(ctx: Context) {
        val token = ctx.cookie(session)
        val userAuth = if (token != null) {
            evaluateUserAuth(token)
        } else null

        if (token != null && userAuth != null) {
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

    fun <T> wsResponse(status: WebSocketStatus, body: T): WebSocketResponse<T> {
        return WebSocketResponseImpl(status.code, body)
    }

    fun handleWsConnection(ctx: WsConnectContext) {
        logger.info("Incoming connection")
        wsUserMap[ctx] = WsUserMapRecord(null, null, false)
    }

    fun handleWsAuth(ctx: WsContext, auth: LifecycleWsMessage) {
        logger.info("Incoming auth request")
        val token = auth.token
        val email = auth.email

        val userAuth = evaluateUserAuth(token)
        if (userAuth == null || userAuth.first != email) {
            ctx.closeSession(WebSocketStatus.UNAUTHORIZED.code, "invalid token")
            return
        }

        when (auth.operation) {
            LifecycleOperation.AUTHENTICATE -> {
                wsUserMap[ctx] = WsUserMapRecord(token, email, true)
                ctx.sendAsClass(wsResponse(WebSocketStatus.SUCCESS, "authentication success"))
                //TODO handling error cases
                deleteToken(token)
                return
            }

            LifecycleOperation.CLOSE -> {
                return handleWsClose(ctx)
            }
        }
    }

    fun handleWsClose(ctx: WsContext) {
        wsUserMap.remove(ctx)
        ctx.sendAsClass(wsResponse(WebSocketStatus.SUCCESS, "web socket close success"))
        return
    }

    private fun deleteToken(token: String): Boolean {
        val edits = db.query { conn ->
            conn.prepareStatement("delete from test_session where token = ?").use { stmt ->
                stmt.setString(1, token)
                stmt.executeUpdate()
            }
        }

        // TODO logging when edits > 1
        return edits > 0
    }

    fun tokenValid(token: String): Boolean {
        val expireTimestamp: Long? =
            db.query { conn ->
                conn.prepareStatement("select expire_timestamp from test_session where token = ?")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                    }
            }
        return expireTimestamp != null && expireTimestamp > Instant.now().epochSecond
    }

    private fun evaluateUserAuth(token: String): Pair<String, Long>? {
        val pair =
            db.query { conn ->
                conn.prepareStatement("select email, expire_timestamp from test_session where token = ?")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getString(1), rs.getLong(2)) else null }
                    }
            }
        return if (pair == null || pair.second < Instant.now().epochSecond) {
            null
        } else {
            pair
        }
    }

    private fun emailPresent(email: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select email from test_users where email = ?").use { stmt ->
                stmt.setString(1, email)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun createSession(email: String, cookieLifetime: Duration = Duration.ofHours(24), isHttpOnly: Boolean = true): Pair<Cookie, Long> {
        val expireTimestamp = Instant.now().plus(cookieLifetime).epochSecond
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
                conn.prepareStatement("insert into test_session (token, expire_timestamp, email) values (?, ?, ?)")
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
                conn.prepareStatement("select * from test_session where email = ?").use { stmt ->
                    stmt.setString(1, email)
                    stmt.executeQuery().use { rs -> rs.next() }
                }
            }

            if (sessionExists) {
                db.query { conn ->
                    conn.prepareStatement("delete from test_session where email = ?").use { stmt ->
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
