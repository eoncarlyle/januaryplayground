/*
 * This source file was generated by the Gradle 'init' task
 */
package com.iainschmitt.januaryplaygroundbackend

import io.javalin.Javalin
import io.javalin.websocket.WsContext
import java.util.concurrent.ConcurrentHashMap

private val userMap = ConcurrentHashMap<WsContext, String>()
private var usercount = 0

class CredentialsDto(val username: String, val password: String)

/*
fun isUsernamePresent(username: String, db: com.iainschmitt.januaryplaygroundbackend.DatabaseHelper): Boolean {
    return db.query { conn ->
        conn.prepareStatement("select username from test_users where username = ?").use { stmt ->
            stmt.setString(1, username)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }
}

fun createSession(db: com.iainschmitt.januaryplaygroundbackend.DatabaseHelper, cookieSecure: Boolean): Cookie {
    val expireTimestamp = Instant.now().plusSeconds(24 * 3600L).epochSecond
    // Do not like that I can't specify a timestamp as `maxAge`
    val token = Generators.randomBasedGenerator().generate().toString()
    val cookie =
            Cookie("session", token, maxAge = 24 * 3600, secure = cookieSecure, isHttpOnly = true)

    try {
        db.query { conn ->
            conn.prepareStatement("insert into test_users (token, password_hash) values (?, ?)")
                    .use { stmt ->
                        stmt.setString(1, token)
                        stmt.setLong(2, expireTimestamp)
                        stmt.executeUpdate()
                    }
        }

        return cookie
    } catch (e: Exception) {
        throw InternalError("`handleAuth` error: ${e.message}")
    }
}

fun tokenValid(db: com.iainschmitt.januaryplaygroundbackend.DatabaseHelper, token: String): Boolean {
    val expireTimestamp: Long? =
            db.query { conn ->
                conn.prepareStatement("select expire_timestamp from test_users where token = ?")
                        .use { stmt ->
                            stmt.setString(1, token)
                            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                        }
            }
    return expireTimestamp != null && expireTimestamp > Instant.now().epochSecond
}

// TODO: abstract out database argument
fun signUpHandler(db: com.iainschmitt.januaryplaygroundbackend.DatabaseHelper, cookieSecure: Boolean): (Context) -> Unit {
    return { ctx: Context ->
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
        val passwordHash = BCrypt.hashpw(dto.password, BCrypt.gensalt())
        if (isUsernamePresent(dto.username, db)) {
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
            val cookie = createSession(db, cookieSecure)
            ctx.cookie(cookie)
            ctx.status(200)
        } catch (e: Exception) {
            throw InternalError("`loginHandler` error: ${e.message}")
        }
    }
}

fun logInHandler(db: com.iainschmitt.januaryplaygroundbackend.DatabaseHelper, cookieSecure: Boolean): (Context) -> Unit {
    return { ctx: Context ->
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
            val cookie = createSession(db, cookieSecure)
            ctx.cookie(cookie)
            ctx.status(201)
        } else {
            throw UnauthorizedResponse("Credentials invalid")
        }
    }
}

 */
fun main(vararg args: String) {
    if (args.size < 2) {
        throw IllegalArgumentException("Empty args")
    }
    val db = DatabaseHelper(args[0])

    val secure =
        when (args[1]) {
            "insecure" -> false
            "secure" -> true
            else -> throw IllegalArgumentException("Invalid `cookieSecure`")
        }

    val app = Javalin.create(/*config*/).start(7070)
    val auth = Auth(db, secure)
    app.get("/health") { ctx -> ctx.result("Up") }
    app.post("/auth/signup") { ctx -> auth.signUpHandler(ctx) }
    app.post("/auth/login") { ctx -> auth.logInHandler(ctx) }

    // TODO: Add auth here
    app.ws("/ws") { ws ->
        ws.onConnect { ctx ->
            val username = "User" + usercount++
            userMap[ctx] = username
            println("Connected: $username")
        }
        ws.onClose { ctx ->
            val username = userMap[ctx]
            userMap.remove(ctx)
            println("Disconnected: $username")
        }
    }

    // startServerEventSimulation()
}

private fun startServerEventSimulation() {
    Thread {
        while (true) {
            Thread.sleep(5000) // Every 5 seconds
            val serverUpdate = "Server time: ${System.currentTimeMillis()}"
            println("Starting server send process")
            userMap.keys.filter { it.session.isOpen }.forEach { session ->
                session.send(serverUpdate)
            }
        }
    }
        .start()
}
