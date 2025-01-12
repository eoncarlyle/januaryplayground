package com.iainschmitt.januaryplaygroundbackend

import com.fasterxml.uuid.Generators
import io.javalin.http.*
import org.mindrot.jbcrypt.BCrypt
import java.time.Instant

class Auth(private val db: DatabaseHelper, private val secure: Boolean) {
    fun signUpHandler(ctx: Context) {
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash = BCrypt.hashpw(dto.password, BCrypt.gensalt())
        if (isUsernamePresent(dto.username)) {
            // If the start of this lamda was `return lamda@`, then doing `return@lamda` below after
            // setting
            // status and return code would do the exit early stuff
            throw ForbiddenResponse("Username `${dto.username}` already exists")
        }

        try {
            db.query { conn ->
                conn.prepareStatement(
                    "insert into test_users (username, password_hash) values (?, ?)"
                )
                    .use { stmt ->
                        stmt.setString(1, dto.username)
                        stmt.setString(2, passwordHash)
                        stmt.executeUpdate()
                    }
            }
            val cookie = createSession(dto.username)
            ctx.cookie(cookie)
            ctx.status(200)
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`signUpHandler` error", e))
        }
    }

    fun logInHandler(ctx: Context) {
        //TODO: clear existing sessions on login
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash: String? =
            db.query { conn ->
                conn.prepareStatement("select * from test_users where username = ?").use { stmt
                    ->
                    stmt.setString(1, dto.username)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getString("password_hash") else null
                    }
                }
            }

        if (passwordHash != null && BCrypt.checkpw(dto.password, passwordHash)) {
            val cookie = createSession(dto.username)
            ctx.cookie(cookie)
            ctx.status(201)
        } else {
            throw UnauthorizedResponse("Credentials invalid")
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


    private fun isUsernamePresent(username: String): Boolean {
        return db.query { conn ->
            conn.prepareStatement("select username from test_users where username = ?").use { stmt ->
                stmt.setString(1, username)
                stmt.executeQuery().use { rs -> rs.next() }
            }
        }
    }

    private fun createSession(username: String): Cookie {
        val expireTimestamp = Instant.now().plusSeconds(24 * 3600L).epochSecond
        // Do not like that I can't specify a timestamp as `maxAge`
        val token = Generators.randomBasedGenerator().generate().toString()
        val cookie =
            Cookie("session", token, maxAge = 24 * 3600, secure = secure, isHttpOnly = true)

        try {
            db.query { conn ->
                conn.prepareStatement("insert into test_session (token, expire_timestamp, username) values (?, ?, ?)")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.setLong(2, expireTimestamp)
                        stmt.setString(3, username)
                        stmt.executeUpdate()
                    }
            }

            return cookie
        } catch (e: Exception) {
            throw InternalError(exceptionMessage("`handleAuth` error", e))
        }
    }

    private fun removeExistingSessions(username: String) {
        try {
            val sessionExists = db.query { conn ->
                conn.prepareStatement("select * from test_session where username = ?").use { stmt ->
                    stmt.setString(1, username)
                    stmt.executeQuery().use { rs -> rs.next() }}
            }

            if (sessionExists) {
                db.query { conn ->
                    conn.prepareStatement("delete from test_session where username = ?").use { stmt ->
                        stmt.setString(1, username)
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
