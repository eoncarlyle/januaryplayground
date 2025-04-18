package com.iainschmitt.januaryplaygroundbackend.marketmaker
import com.iainschmitt.januaryplaygroundbackend.shared.CredentialsDto

import kotlinx.coroutines.*
import io.ktor.http.*
import io.ktor.client.request.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.jackson.*

fun main() = runBlocking {
    val authClient = AuthClient()

    try {
        // Login
        val loginResponse = authClient.login("testmm@iainschmitt.com", "myTestMmPassword")
        println("Login successful: $loginResponse")

        // Check authentication
        val authStatus = authClient.evaluateAuth()
        println("Auth status: $authStatus")

        // Example of WebSocket connection
        val job = launch {
            authClient.connectWebSocket(
                email = "testmm@iainschmitt.com",
                onOpen = { println("WebSocket connection opened") },
                onMessage = { println("Received message: $it") },
                onClose = { code, reason -> println("Connection closed: $code, $reason") }
            )
        }

        // Let the WebSocket run for a bit
        delay(10000)

        // Cancel the WebSocket job
        job.cancel()

        // Logout
        val logoutSuccess = authClient.logout()
        println("Logout successful: $logoutSuccess")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    } finally {
        authClient.close()
    }
}
