package com.iainschmitt.januaryplaygroundbackend.shared

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.javalin.websocket.WsContext

enum class WebSocketResponseStatus(val code: Int) {
    SUCCESS(200),
    CREATED(201),
    ACCEPTED(202), // Recieved but not acted upon
    BAD_REQUEST(400),
    UNAUTHORIZED(401),
    ERROR(500)
}

// Will have fields added to it
class WsUserMapRecord(val token: String?, val email: String?, val authenticated: Boolean, val tickers: List<Ticker>)

class WsUserMap {
    private val map = HashMap<WsContext, WsUserMapRecord>()

    fun forEachLiveSocket(action: (WsContext) -> Unit): Int {
        synchronized(this) {
            val aliveSockets = map.keys.filter { it.session.isOpen && map[it]?.authenticated ?: false }
            aliveSockets.forEach { ctx -> action(ctx) }
            return aliveSockets.size
        }
    }

    fun forEachSubscribingLiveSocket(ticker: Ticker, action: (WsContext) -> Unit): Int {
        synchronized(this) {
            val aliveSockets = map.keys.filter {
                it.session.isOpen && (map[it]?.authenticated ?: false) && (map[it]?.tickers?.contains(ticker) ?: false)
            }
            aliveSockets.forEach { ctx -> action(ctx) }
            return aliveSockets.size
        }
    }

    fun set(ctx: WsContext, record: WsUserMapRecord) {
        synchronized(this) {
            map[ctx] = record
        }
    }

    fun remove(ctx: WsContext) {
        synchronized(this) {
            map.remove(ctx)
        }
    }
}


enum class WebSocketLifecycleOperation {
    @JsonAlias("authenticate")
    AUTHENTICATE,

    @JsonAlias("close")
    CLOSE
}

// It isn't per se neccessary to have all of this - the context realistically will have all that we
// actually need.
// However, being able to log this out will almost certainly be helpful

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type", include = JsonTypeInfo.As.EXISTING_PROPERTY)
@JsonSubTypes(
    JsonSubTypes.Type(
        value = ClientLifecycleMessage::class,
        name = "clientLifecycle"
    ),
    JsonSubTypes.Type(value = ServerLifecycleMessage::class, name = "serverLifecycle"),
    JsonSubTypes.Type(value = QuoteMessage::class, name = "outgoingQuote"),
    JsonSubTypes.Type(value = ServerTimeMessage::class, name = "outgoingServerTime"),
)
interface WebSocketMessage {
    val type: String // Needed for deserialisation
}

interface ServerWebSocketMessage : WebSocketMessage {
    val webSocketResponseStatus: WebSocketResponseStatus
}

data class OutgoingError(
    override val webSocketResponseStatus: WebSocketResponseStatus,
    val errorDescription: String
) : ServerWebSocketMessage {
    override val type = "Error"
}

data class ClientLifecycleMessage(
    val token: String,
    val operation: WebSocketLifecycleOperation,
    val email: String,
    val tickers: List<Ticker>
) : WebSocketMessage {
    override val type: String = "clientLifecycle"
}

data class ServerLifecycleMessage(
    val operation: WebSocketLifecycleOperation,
    override val webSocketResponseStatus: WebSocketResponseStatus,
    val email: String?,
    val body: String,
) : ServerWebSocketMessage {
    override val type: String = "serverLifecycle"
}

//data class OutgoingMarketLifecycle(
//    override val type: String = "incomingLifecycle",
//    val operation: WebSocketLifecycleOperation
//) : WebSocketMessage

data class ServerTimeMessage(
    val time: Long
) : WebSocketMessage {
    override val type: String = "outgoingServerTime"
}

data class QuoteMessage(
    val quote: Quote
) : WebSocketMessage {
    override val type: String = "outgoingQuote"
}
