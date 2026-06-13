package com.fxvelociraptor.trading.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class SignalType { BUY, SELL }

@Parcelize
data class TradeSignal(
    val symbol: String,
    val type: SignalType,
    val basePattern: String,    // "RBD", "DBR", "RBR", "DBD"
    val sniperType: String,     // "ST1", "ST2", "ST3", "ST4"
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val confidence: Int,        // 0-100
    val timestamp: Long,
    val reason: String
) : Parcelable {
    val riskReward: Double get() {
        val risk = Math.abs(entryPrice - stopLoss)
        val reward = Math.abs(entryPrice - takeProfit)
        return if (risk > 0) reward / risk else 0.0
    }
}

data class BasePattern(
    val type: String,           // "RBD", "DBR", "RBR", "DBD"
    val signalType: SignalType,
    val zoneHigh: Double,       // Haut de la zone Supply/Demand
    val zoneLow: Double,        // Bas de la zone
    val startIndex: Int,
    val endIndex: Int,
    val strength: Int           // 1-5
) {
    val zoneSize: Double get() = zoneHigh - zoneLow
    val zoneMidpoint: Double get() = (zoneHigh + zoneLow) / 2
}

data class SniperEntry(
    val type: String,           // "ST1", "ST2", "ST3", "ST4", "WAIT"
    val entryPrice: Double,
    val isValid: Boolean,
    val confidence: Int         // 0-100
)

@Parcelize
data class Trade(
    val id: Long = 0,
    val ticket: Long,
    val symbol: String,
    val type: String,           // "BUY" ou "SELL"
    val lotSize: Double,
    val openPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val openTime: Long,
    val closeTime: Long = 0,
    val closePrice: Double = 0.0,
    val profit: Double = 0.0,
    val status: TradeStatus = TradeStatus.OPEN,
    val basePattern: String = "",
    val sniperType: String = "",
    val comment: String = ""
) : Parcelable {
    val isOpen: Boolean get() = status == TradeStatus.OPEN
    val riskPoints: Double get() = Math.abs(openPrice - stopLoss)
    val rewardPoints: Double get() = Math.abs(openPrice - takeProfit)
}

enum class TradeStatus { OPEN, CLOSED_TP, CLOSED_SL, CLOSED_MANUAL, CLOSED_DANGER }

data class PerformanceStats(
    val totalTrades: Int = 0,
    val winningTrades: Int = 0,
    val losingTrades: Int = 0,
    val totalProfit: Double = 0.0,
    val totalLoss: Double = 0.0,
    val netProfit: Double = 0.0,
    val winRate: Double = 0.0,
    val averageRR: Double = 0.0,
    val bestTrade: Double = 0.0,
    val worstTrade: Double = 0.0,
    val currentDrawdown: Double = 0.0,
    val maxDrawdown: Double = 0.0
)

data class BotConfig(
    val isActive: Boolean = false,
    val symbols: List<String> = listOf("EURUSD", "XAUUSD"),
    val timeframe: String = "H1",
    val riskPercent: Double = 1.0,
    val rrRatio: Double = 2.0,
    val maxFloatLoss: Double = 50.0,
    val maxOpenTrades: Int = 3,
    val enableAutoClose: Boolean = true,
    val enableNotifications: Boolean = true,
    val scanIntervalSeconds: Int = 30,
    val minConfidence: Int = 70,    // Confidence minimum pour ouvrir un trade
    val enableST1: Boolean = true,
    val enableST2: Boolean = true,
    val enableST3: Boolean = true,
    val enableST4: Boolean = true
)
