package com.iainschmitt.januaryplaygroundbackend

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
    val code: Int
    val body: T
}

class WebSocketResponseImpl<T>(
    override val code: Int,
    override val body: T
) : WebSocketResponse<T>

// Will have fields added to it
class WsUserMapRecord(val token: String?, val email: String?, val authenticated: Boolean)
typealias WsUserMap = ConcurrentHashMap<WsContext, WsUserMapRecord>

enum class LifecycleOperation {
    @JsonAlias("authenticate")
    AUTHENTICATE,
    @JsonAlias("close")
    CLOSE;
}

// It isn't per se neccessary to have all of this - the context realistically will have all that we actually need.
// However, being able to log this out will almost certainly be helpful

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = LifecycleWsMessage::class, name = "lifecycle")
)
sealed interface WebSocketMessage {
    val type: String //Needed for serialisation?
    val token: String
    val email: String
}

data class LifecycleWsMessage(
    override val type: String = "lifecycle",
    override val token: String,
    override val email: String,
    val operation: LifecycleOperation
) : WebSocketMessage
