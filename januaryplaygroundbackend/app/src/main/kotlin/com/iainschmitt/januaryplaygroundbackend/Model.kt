package com.iainschmitt.januaryplaygroundbackend

import io.javalin.websocket.WsContext
import java.util.concurrent.ConcurrentHashMap

class CredentialsDto(val email: String, val password: String)

enum class WebSocketStatus(val code: Int) {
    SUCCESS(200),
    CREATED(201),
    ACCEPTED(202), // Recieved but not acted upon
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    ERROR(500)
}

interface WebSocketResponse<T> {
    val status: WebSocketStatus
    val body: T
}

class WebSocketResponseImpl<T>(
    override val status: WebSocketStatus,
    override val body: T
) : WebSocketResponse<T>

// Will have fields added to it
class WsUserMapRecord(val token: String?, val email: String?, val authenticated: Boolean)
typealias WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>

enum class AuthWsMessageOperation(val value: String) {
    AUTHENTICATE("authenticate"),
    CLOSE("close");
}

// It isn't per se neccessary to have all of this - the context realistically will have all that we actually need.
// However, being able to log this out will almost certainly be helpful
interface WebSocketMessage {
    val token: String;
    val email: String;
}

class AuthWsMessage(override val token: String, override val email: String, val operation: AuthWsMessageOperation) :
    WebSocketMessage
