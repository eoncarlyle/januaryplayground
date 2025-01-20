package com.iainschmitt.januaryplaygroundbackend

import com.fasterxml.uuid.Generators
import io.javalin.http.*
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant

class Auth(private val db: DatabaseHelper, private val secure: Boolean) {
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
            val cookie = createSession(dto.email)
            ctx.cookie(cookie)
            ctx.status(201)
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`signUpHandler` error", e))
        }
    }

    fun logInHandler(ctx: Context) {
        //TODO: clear existing sessions on login
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash: String? =
            db.query { conn ->
                conn.prepareStatement("select * from test_users where email = ?").use { stmt
                    ->
                    stmt.setString(1, dto.email)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("password_hash") else null
                    }
                }
            }

        if (passwordHash != null && BCrypt.checkpw(dto.password, passwordHash)) {
            val cookie = createSession(dto.email)
            ctx.cookie(cookie)
            ctx.status(200)
        } else {
            throw ForbiddenResponse("email or password not found")
        }
    }

    fun evaluateAuthHandler(ctx: Context) {
        val token = ctx.cookie("session")
        val user = if (token != null) evaluateUserEmail(token) else null
        if (token != null && user != null) {
            ctx.json(mapOf("email" to user))
            ctx.status(200)
        } else {
            ctx.result("Fail")
            ctx.status(403)
        }
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

    private fun evaluateUserEmail(token: String): String? {
        val pair =
            db.query { conn ->
                conn.prepareStatement("select expire_timestamp, email from test_session where token = ?")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.executeQuery().use { rs -> if (rs.next()) Pair(rs.getLong(1), rs.getString(2)) else null }
                    }
            }
        return if (pair == null || pair.first < Instant.now().epochSecond) {
            null
        } else {
            pair.second
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

    private fun createSession(email: String): Cookie {
        val expireTimestamp = Instant.now().plusSeconds(24 * 3600L).epochSecond
        // Do not like that I can't specify a timestamp as `maxAge`
        val token = Generators.randomBasedGenerator().generate().toString()
        val cookie =
            Cookie(
                "session",
                token,
                maxAge = 24 * 3600,
                secure = secure,
                sameSite = if (secure) SameSite.STRICT else SameSite.LAX,
                isHttpOnly = true,
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

            return cookie
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
}
