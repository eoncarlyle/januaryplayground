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

val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        jackson()
    }
}

suspend fun login(): String {
    val response = client.post("http://localhost:7070/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(CredentialsDto("testmm@iainschmitt.com", "myTestMmPassword"))
    }
    return response.body<Map<String,String>>().toString()
}

fun main() = runBlocking {
    val a = async {login()}
    val b = a.await()
    println(b)
}

