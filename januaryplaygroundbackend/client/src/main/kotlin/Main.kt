package com.iainschmitt.januaryplaygroundbackend.marketmaker

import kotlinx.coroutines.*

fun main() = runBlocking {
    val backendClient = BackendClient()

    try {
        val loginResponse = backendClient.login("testmm@iainschmitt.com", "myTestMmPassword")
        // Don't transfer these over when using the client
        //println("Login successful: $loginResponse")

        val authStatus = backendClient.evaluateAuth()
        println("Auth status: $authStatus")

        val job = launch {
            backendClient.connectWebSocket(
                email = "testmm@iainschmitt.com",
                onOpen = { println("WebSocket connection opened") },
                onMessage = { println("Received message: $it") },
                onClose = { code, reason -> println("Connection closed: $code, $reason") }
            )
        }

        delay(10000)
        job.cancel()
        val logoutSuccess = backendClient.logout()
        println("Logout successful: $logoutSuccess")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        backendClient.close()
    }
}
