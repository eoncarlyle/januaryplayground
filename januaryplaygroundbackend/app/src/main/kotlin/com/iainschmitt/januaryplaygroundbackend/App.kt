/*
 * This source file was generated by the Gradle 'init' task
 */
package com.iainschmitt.januaryplaygroundbackend

import DatabaseHelper
import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.ForbiddenResponse
import io.javalin.http.UnauthorizedResponse
import io.javalin.websocket.WsContext
import java.util.concurrent.ConcurrentHashMap

private val userMap = ConcurrentHashMap<WsContext, String>()
private var usercount = 0

class CredentialsDto(val username: String, val passwordHash: String)

fun getDbTest(id: Long, db: DatabaseHelper): String? {
    return db.query { conn ->
        conn.prepareStatement("select * from test_table where id = ?").use { stmt ->
            stmt.setLong(1, id)
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("value") else null }
        }
    }
}

fun isUsernamePresent(username: String, db: DatabaseHelper): Boolean {
    return db.query { conn ->
        conn.prepareStatement("select username from test_users where username = ?").use { stmt ->
            stmt.setString(1, username)
            stmt.executeQuery().use { rs -> rs.next() }
        }
    }
}

// TODO: abstract out database argument
fun signUpHandler(db: DatabaseHelper): (Context) -> Unit {
    return { ctx: Context ->
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)
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
                        stmt.setString(2, dto.passwordHash)
                        stmt.executeUpdate()
                    }
            }
            ctx.status(201)
        } catch (e: Exception) {
            throw InternalError("`loginHandler` error: ${e.message}")
        }
    }
}

fun logInHandler(db: DatabaseHelper): (Context) -> Unit {
    return { ctx: Context ->
        val dto = ctx.bodyAsClass(CredentialsDto::class.java)

        val exists =
            db.query { conn ->
                conn.prepareStatement(
                    "select * from test_users where username = ? and password_hash = ?"
                )
                    .use { stmt ->
                        stmt.setString(1, dto.username)
                        stmt.setString(2, dto.passwordHash)
                        stmt.executeQuery().use { rs -> rs.next() }
                    }
            }

        if (exists) {
            ctx.status(200)
        } else {
            throw UnauthorizedResponse("Credentials invalid")
        }
    }
}

fun main(vararg args: String) {
    if (args.isEmpty()) {
        throw IllegalArgumentException("Empty args")
    }
    val db = DatabaseHelper(args[0])
    println(getDbTest(2, db))

    val app = Javalin.create(/*config*/).start(7070)
    app.get("/health") { ctx -> ctx.result("Up") }
    app.post("/auth/signup", signUpHandler(db))
    app.post("/auth/login", logInHandler(db))

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
