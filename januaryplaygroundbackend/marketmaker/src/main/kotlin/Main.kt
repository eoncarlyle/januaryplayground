package com.iainschmitt.januaryplaygroundbackend.marketmaker

import kotlinx.coroutines.*

fun main() = runBlocking {
    val authClient = AuthClient()

    try {
        val loginResponse = authClient.login("testmm@iainschmitt.com", "myTestMmPassword")
        println("Login successful: $loginResponse")

        val authStatus = authClient.evaluateAuth()
        println("Auth status: $authStatus")

        val job = launch {
            authClient.connectWebSocket(
                email = "testmm@iainschmitt.com",
                onOpen = { println("WebSocket connection opened") },
                onMessage = { println("Received message: $it") },
                onClose = { code, reason -> println("Connection closed: $code, $reason") }
            )
        }

        delay(10000)
        job.cancel()
        val logoutSuccess = authClient.logout()
        println("Logout successful: $logoutSuccess")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        authClient.close()
    }
}
