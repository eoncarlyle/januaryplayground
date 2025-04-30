package com.iainschmitt.januaryplaygroundbackend.app

import com.iainschmitt.januaryplaygroundbackend.shared.Ticker
import com.iainschmitt.januaryplaygroundbackend.shared.TickerRecord
import java.util.AbstractMap.SimpleEntry
import java.util.concurrent.Semaphore

class TransactionSemaphores(
    private val db: DatabaseHelper
) {
    private val tickers = getAllTickers()
    private val semaphores: Map<Ticker, Semaphore> =
        tickers.map { ticker -> SimpleEntry(ticker, Semaphore(1)) }.associate { it.key to it.value }

    private fun getAllTickers(): List<Ticker> {
        return db.query { conn ->
            val tickers = mutableListOf<TickerRecord>()
            conn.prepareStatement("select symbol, open from ticker").use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        tickers.add(TickerRecord(rs.getString("symbol"), rs.getInt("open")))
                    }
                }
            }
            tickers
        }.map { record -> record.ticker }
    }


    fun getSemaphore(ticker: Ticker): Semaphore? {
        return semaphores[ticker]
    }
}
