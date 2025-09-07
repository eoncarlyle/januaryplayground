package com.iainschmitt.januaryplaygroundbackend.shared

import arrow.core.Either
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonIgnore
import io.javalin.http.Context
import kotlinx.serialization.Serializable

class CredentialsDto(val email: String, val password: String)

class OrchestratedCredentialsDto(
    val orchestratorEmail: String, val userEmail: String, val userPassword: String, val initialCreditBalance: Int,
    val initialPositions: List<Pair<Ticker, Int>> = listOf()
)

class LiquidateOrchestratedUserDto(val orchestratorEmail: String, val targetUserEmail: String)

class LiquidateAllOrchestratedUsersDto(val orchestratorEmail: String)


@Serializable
class CreditTransferDto(val sendingUserEmail: String, val targetUserEmail: String, val creditAmount: Int)

inline fun <reified T> parseCtxBody(ctx: Context): Either<Pair<Int, String>, T> {
    return Either.catch { ctx.bodyAsClass(T::class.java) }
        .mapLeft { 400 to "Bad request: could not deserialize" }
}

enum class AccountType {
    STANDARD,
    ORCHESTRATOR,
    ADMIN
}

inline fun <reified T> parseCtxBodyMiddleware(
    ctx: Context,
    f: (T) -> Unit
) {
    parseCtxBody<T>(ctx).onRight { dto -> f(dto) }.onLeft { error ->
        ctx.status(error.first)
        ctx.json("message" to error.second)
    }
}

typealias Ticker = String

fun Ticker.unknownMessage() = "Unknown ticker '$this'"

enum class TradeType {
    //@JsonAlias("buy")
    BUY,

    //@JsonAlias("sell")
    SELL;

    fun isBuy(): Boolean {
        return this == BUY
    }

    fun isSell(): Boolean {
        return this == SELL
    }
}

enum class OrderType {
    Market,
    Limit,
    FillOrKill,
    AllOrNothing
}

enum class MarketLifecycleOperation {
    @JsonAlias("open")
    OPEN,

    @JsonAlias("close")
    CLOSE;
}


enum class PositionType {
    LONG,
    SHORT
}

fun getPositionType(ordinal: Int): PositionType {
    return when (ordinal) {
        0 -> PositionType.LONG
        1 -> PositionType.SHORT
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

fun getTradeType(ordinal: Int): TradeType {
    return when (ordinal) {
        0 -> TradeType.BUY
        1 -> TradeType.SELL
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

typealias SortedOrderBook = MutableMap<Int, ArrayList<OrderBookEntry>>

// Ticker, price, size: eventually should move this to a dedicated class, this is asking for problems
data class OrderBookEntry(
    val id: Int,
    val user: String,
    val ticker: Ticker,
    val tradeType: TradeType,
    val size: Int,
    val price: Int,
    val orderType: OrderType,
    val receivedTick: Long,
    var finalSize: Int = 0,
)

fun getOrderType(ordinal: Int): OrderType {
    return when (ordinal) {
        0 -> OrderType.Market
        1 -> OrderType.Limit
        2 -> OrderType.FillOrKill
        3 -> OrderType.AllOrNothing
        else -> throw IllegalArgumentException("Illegal OrderType ordinal $ordinal")
    }
}

interface Order {
    val ticker: Ticker;
    val tradeType: TradeType;
    val orderType: OrderType;
    val size: Int;
    val email: String;

    @JsonIgnore
    fun sign(): Int {
        return if (this.tradeType == TradeType.BUY) 1 else -1
    }

    @JsonIgnore
    fun isBuy(): Boolean {
        return tradeType.isBuy()
    }
}

data class PositionRecord(
    val id: Int,
    val ticker: Ticker,
    val positionType: PositionType,
    val size: Int
)

