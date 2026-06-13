package com.fxvelociraptor.trading.strategy

import android.util.Log
import com.fxvelociraptor.trading.api.Candle
import com.fxvelociraptor.trading.model.TradeSignal
import com.fxvelociraptor.trading.model.SignalType
import com.fxvelociraptor.trading.model.BasePattern
import com.fxvelociraptor.trading.model.SniperEntry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Moteur de stratégie FXVelociraptor
 *
 * Implémente:
 * - Détection des BASES: RBD (Sell), DBR (Buy), RBR (Buy), DBD (Sell)
 * - Supply Zone / Demand Zone
 * - Sniper Entry ST1, ST2, ST3, ST4
 * - Gestion automatique SL/TP
 * - Détection de danger pour coupure automatique du SL
 */
class FXVelociraptorStrategy(
    private val config: StrategyConfig = StrategyConfig()
) {

    companion object {
        private const val TAG = "FXVelociraptorStrategy"

        // Seuils de détection des patterns
        private const val MIN_MOVE_PERCENT = 0.3      // Mouvement minimum pour Rally/Drop (%)
        private const val MAX_BASE_CANDLES = 5         // Nombre max de bougies dans une Base
        private const val MIN_BASE_CANDLES = 1         // Nombre min
        private const val ZONE_PROXIMITY_PERCENT = 0.1 // Proximité de la zone pour entrée (%)
    }

    // ============================================================
    // ANALYSE PRINCIPALE - appelée à chaque nouveau tick/bougie
    // ============================================================

    /**
     * Analyser les bougies et générer un signal de trading
     * @param candles Liste des bougies (du plus ancien au plus récent)
     * @param symbol Symbole analysé (EURUSD, XAUUSD, etc.)
     * @return Signal de trading ou null si pas d'opportunité
     */
    fun analyze(candles: List<Candle>, symbol: String): TradeSignal? {
        if (candles.size < 20) {
            Log.d(TAG, "Pas assez de bougies pour analyser $symbol")
            return null
        }

        Log.d(TAG, "Analyse $symbol - ${candles.size} bougies")

        // 1. Identifier la structure de marché (tendance)
        val marketStructure = identifyMarketStructure(candles)
        Log.d(TAG, "Structure: $marketStructure")

        // 2. Détecter les patterns de base (RBD, DBR, RBR, DBD)
        val bases = detectBases(candles)
        if (bases.isEmpty()) {
            Log.d(TAG, "Aucune base détectée sur $symbol")
            return null
        }

        // 3. Filtrer les bases en accord avec la structure
        val validBases = filterBasesByStructure(bases, marketStructure)
        if (validBases.isEmpty()) return null

        // 4. Chercher une entrée Sniper sur la base la plus récente
        val latestBase = validBases.last()
        val currentPrice = candles.last().close
        val sniperEntry = findSniperEntry(candles, latestBase, currentPrice)

        // 5. Générer le signal
        return generateSignal(symbol, latestBase, sniperEntry, currentPrice, candles)
    }

    // ============================================================
    // DÉTECTION DE DANGER - Stop Loss automatique
    // ============================================================

    /**
     * Vérifier si une position est en danger
     * Conditions de danger FXVelociraptor:
     * - Prix a cassé la structure en sens inverse
     * - Le float (perte) dépasse le seuil de danger
     * - Pattern d'inversion détecté
     */
    fun isDangerDetected(
        candles: List<Candle>,
        positionType: String,  // "BUY" ou "SELL"
        openPrice: Double,
        currentProfit: Double,
        stopLoss: Double
    ): DangerLevel {

        val currentPrice = candles.last().close
        val lastCandles = candles.takeLast(10)

        // Danger CRITIQUE: SL déjà touché ou très proche (5% de distance)
        val slDistance = abs(currentPrice - stopLoss) / currentPrice * 100
        if (slDistance < 0.05) {
            return DangerLevel.CRITICAL
        }

        // Danger ÉLEVÉ: Le float dépasse le seuil maximum
        if (currentProfit < -config.maxFloatLoss) {
            Log.w(TAG, "⚠️ Float maximum dépassé: $currentProfit")
            return DangerLevel.HIGH
        }

        // Danger MOYEN: Structure brisée dans la direction opposée
        if (positionType == "BUY") {
            val isStructureBroken = detectBearishBreak(lastCandles, openPrice)
            if (isStructureBroken) {
                Log.w(TAG, "⚠️ Structure bearish détectée sur position BUY")
                return DangerLevel.MEDIUM
            }
            // Vérifier si le prix est en-dessous du point d'entrée sniper
            if (currentPrice < openPrice * (1 - config.dangerThresholdPercent / 100)) {
                return DangerLevel.MEDIUM
            }
        } else { // SELL
            val isStructureBroken = detectBullishBreak(lastCandles, openPrice)
            if (isStructureBroken) {
                Log.w(TAG, "⚠️ Structure bullish détectée sur position SELL")
                return DangerLevel.MEDIUM
            }
            if (currentPrice > openPrice * (1 + config.dangerThresholdPercent / 100)) {
                return DangerLevel.MEDIUM
            }
        }

        // Danger FAIBLE: Accumulation (ignorer selon règle FXVelociraptor)
        if (isAccumulation(lastCandles)) {
            return DangerLevel.LOW
        }

        return DangerLevel.NONE
    }

    // ============================================================
    // CALCUL SL/TP AUTOMATIQUE
    // ============================================================

    /**
     * Calculer le Stop Loss optimal selon la stratégie
     * SL placé au-delà de la base (zone supply/demand)
     */
    fun calculateStopLoss(
        basePattern: BasePattern,
        signalType: SignalType,
        bufferPips: Double = 3.0,
        pipValue: Double = 0.0001
    ): Double {
        return when (signalType) {
            SignalType.BUY -> {
                // SL en-dessous du bas de la Demand Zone
                basePattern.zoneLow - (bufferPips * pipValue)
            }
            SignalType.SELL -> {
                // SL au-dessus du haut de la Supply Zone
                basePattern.zoneHigh + (bufferPips * pipValue)
            }
        }
    }

    /**
     * Calculer le Take Profit (projection de la zone précédente)
     * Ratio Risk:Reward minimum 1:2 selon FXVelociraptor
     */
    fun calculateTakeProfit(
        entryPrice: Double,
        stopLoss: Double,
        signalType: SignalType,
        rrRatio: Double = 2.0
    ): Double {
        val risk = abs(entryPrice - stopLoss)
        return when (signalType) {
            SignalType.BUY -> entryPrice + (risk * rrRatio)
            SignalType.SELL -> entryPrice - (risk * rrRatio)
        }
    }

    /**
     * Calculer la taille de lot selon le risque max
     */
    fun calculateLotSize(
        accountBalance: Double,
        riskPercent: Double,       // ex: 1.0 pour 1%
        entryPrice: Double,
        stopLoss: Double,
        pipValue: Double = 10.0,   // Valeur d'un pip pour 1 lot standard
        symbol: String = "EURUSD"
    ): Double {
        val riskAmount = accountBalance * (riskPercent / 100)
        val slPips = abs(entryPrice - stopLoss) / 0.0001
        val lotSize = riskAmount / (slPips * pipValue)

        // Limiter entre min et max
        return lotSize.coerceIn(config.minLotSize, config.maxLotSize)
            .let { (it * 100).toInt() / 100.0 } // Arrondir à 2 décimales
    }

    // ============================================================
    // DÉTECTION DES PATTERNS DE BASE
    // ============================================================

    private fun detectBases(candles: List<Candle>): List<BasePattern> {
        val bases = mutableListOf<BasePattern>()
        val n = candles.size

        // Scanner les bougies pour détecter les séquences R-B-D, D-B-R, etc.
        var i = 3
        while (i < n - 3) {
            // Analyser la séquence autour de i
            val prevMove = getMoveType(candles, max(0, i - 8), i - 1)
            val baseEnd = findBaseEnd(candles, i)
            if (baseEnd > i) {
                val nextMove = getMoveType(candles, baseEnd, min(n - 1, baseEnd + 8))

                val pattern = when {
                    // RBD: Rally → Base → Drop = SELL
                    prevMove == MoveType.RALLY && nextMove == MoveType.DROP -> {
                        BasePattern(
                            type = "RBD",
                            signalType = SignalType.SELL,
                            zoneHigh = candles.subList(i, baseEnd + 1).maxOf { it.high },
                            zoneLow = candles.subList(i, baseEnd + 1).minOf { it.low },
                            startIndex = i,
                            endIndex = baseEnd,
                            strength = calculateBaseStrength(candles, i, baseEnd)
                        )
                    }
                    // DBR: Drop → Base → Rally = BUY
                    prevMove == MoveType.DROP && nextMove == MoveType.RALLY -> {
                        BasePattern(
                            type = "DBR",
                            signalType = SignalType.BUY,
                            zoneHigh = candles.subList(i, baseEnd + 1).maxOf { it.high },
                            zoneLow = candles.subList(i, baseEnd + 1).minOf { it.low },
                            startIndex = i,
                            endIndex = baseEnd,
                            strength = calculateBaseStrength(candles, i, baseEnd)
                        )
                    }
                    // RBR: Rally → Base → Rally = BUY (continuation)
                    prevMove == MoveType.RALLY && nextMove == MoveType.RALLY -> {
                        BasePattern(
                            type = "RBR",
                            signalType = SignalType.BUY,
                            zoneHigh = candles.subList(i, baseEnd + 1).maxOf { it.high },
                            zoneLow = candles.subList(i, baseEnd + 1).minOf { it.low },
                            startIndex = i,
                            endIndex = baseEnd,
                            strength = calculateBaseStrength(candles, i, baseEnd)
                        )
                    }
                    // DBD: Drop → Base → Drop = SELL (continuation)
                    prevMove == MoveType.DROP && nextMove == MoveType.DROP -> {
                        BasePattern(
                            type = "DBD",
                            signalType = SignalType.SELL,
                            zoneHigh = candles.subList(i, baseEnd + 1).maxOf { it.high },
                            zoneLow = candles.subList(i, baseEnd + 1).minOf { it.low },
                            startIndex = i,
                            endIndex = baseEnd,
                            strength = calculateBaseStrength(candles, i, baseEnd)
                        )
                    }
                    else -> null
                }

                pattern?.let { bases.add(it) }
                i = baseEnd + 1
            } else {
                i++
            }
        }

        Log.d(TAG, "Bases détectées: ${bases.size} (${bases.map { it.type }})")
        return bases
    }

    private fun getMoveType(candles: List<Candle>, start: Int, end: Int): MoveType {
        if (start >= end || end >= candles.size) return MoveType.NEUTRAL

        val startPrice = candles[start].close
        val endPrice = candles[end].close
        val movePercent = (endPrice - startPrice) / startPrice * 100

        return when {
            movePercent > MIN_MOVE_PERCENT -> MoveType.RALLY
            movePercent < -MIN_MOVE_PERCENT -> MoveType.DROP
            else -> MoveType.NEUTRAL
        }
    }

    private fun findBaseEnd(candles: List<Candle>, start: Int): Int {
        // Une base = bougies avec range limité (consolidation)
        val baseHighRef = candles[start].high
        val baseLowRef = candles[start].low
        val baseRange = baseHighRef - baseLowRef

        var end = start
        for (i in start + 1 until min(start + MAX_BASE_CANDLES + 1, candles.size)) {
            val candleRange = candles[i].high - candles[i].low
            // Encore dans la base si la bougie ne dépasse pas 2x le range initial
            if (candleRange <= baseRange * 2.5 &&
                candles[i].high <= baseHighRef * 1.002 &&
                candles[i].low >= baseLowRef * 0.998) {
                end = i
            } else {
                break
            }
        }
        return end
    }

    private fun calculateBaseStrength(candles: List<Candle>, start: Int, end: Int): Int {
        // Force 1-5 selon: nombre de touches, vitesse de départ
        val touches = (end - start + 1).coerceIn(1, 3)
        val departSpeed = if (end + 2 < candles.size) {
            val body = abs(candles[end + 1].close - candles[end + 1].open)
            val range = candles[end + 1].high - candles[end + 1].low
            if (range > 0 && body / range > 0.7) 2 else 1
        } else 1
        return (touches + departSpeed).coerceIn(1, 5)
    }

    // ============================================================
    // SNIPER ENTRY
    // ============================================================

    private fun findSniperEntry(
        candles: List<Candle>,
        base: BasePattern,
        currentPrice: Double
    ): SniperEntry {
        val zoneHigh = base.zoneHigh
        val zoneLow = base.zoneLow
        val zoneSize = zoneHigh - zoneLow

        // ST1: Entrée directe sur la zone (entrée la plus agressive)
        val inZone = when (base.signalType) {
            SignalType.BUY -> currentPrice in zoneLow..(zoneLow + zoneSize * 0.3)
            SignalType.SELL -> currentPrice in (zoneHigh - zoneSize * 0.3)..zoneHigh
        }

        // ST2: Entrée sur le QML (Quasi-Mini Level) = 50% de la zone
        val qml = (zoneHigh + zoneLow) / 2
        val nearQml = abs(currentPrice - qml) / zoneSize < 0.15

        // ST3: Entrée sur pullback après cassure
        val recentCandles = candles.takeLast(5)
        val hasPullback = detectPullback(recentCandles, base.signalType)

        // ST4: Entrée sur Order Block (dernière bougie bullish avant drop / bearish avant rally)
        val orderBlock = findOrderBlock(candles, base)

        val type = when {
            inZone -> "ST1"
            nearQml -> "ST2"
            hasPullback -> "ST3"
            orderBlock != null -> "ST4"
            else -> "WAIT"
        }

        val entryPrice = when (type) {
            "ST1" -> if (base.signalType == SignalType.BUY) zoneLow + zoneSize * 0.1
                     else zoneHigh - zoneSize * 0.1
            "ST2" -> qml
            "ST3" -> currentPrice
            "ST4" -> orderBlock ?: currentPrice
            else -> currentPrice
        }

        return SniperEntry(
            type = type,
            entryPrice = entryPrice,
            isValid = type != "WAIT",
            confidence = when(type) {
                "ST1" -> 85
                "ST2" -> 75
                "ST3" -> 70
                "ST4" -> 80
                else -> 0
            }
        )
    }

    private fun detectPullback(candles: List<Candle>, signalType: SignalType): Boolean {
        if (candles.size < 3) return false
        val recent = candles.takeLast(3)
        return when (signalType) {
            SignalType.BUY -> recent[0].close > recent[1].close && recent[2].close > recent[1].close
            SignalType.SELL -> recent[0].close < recent[1].close && recent[2].close < recent[1].close
        }
    }

    private fun findOrderBlock(candles: List<Candle>, base: BasePattern): Double? {
        // Dernière bougie opposée avant la base
        for (i in base.startIndex - 1 downTo max(0, base.startIndex - 5)) {
            val candle = candles[i]
            val isOrderBlock = when (base.signalType) {
                SignalType.BUY -> candle.isBearish && candle.body > 0
                SignalType.SELL -> candle.isBullish && candle.body > 0
            }
            if (isOrderBlock) {
                return when (base.signalType) {
                    SignalType.BUY -> candle.high
                    SignalType.SELL -> candle.low
                }
            }
        }
        return null
    }

    // ============================================================
    // STRUCTURE DE MARCHÉ
    // ============================================================

    private fun identifyMarketStructure(candles: List<Candle>): MarketStructure {
        val highs = mutableListOf<Pair<Int, Double>>()
        val lows = mutableListOf<Pair<Int, Double>>()

        // Identifier HH, HL, LH, LL
        for (i in 2 until candles.size - 2) {
            if (candles[i].high > candles[i-1].high && candles[i].high > candles[i+1].high &&
                candles[i].high > candles[i-2].high && candles[i].high > candles[i+2].high) {
                highs.add(Pair(i, candles[i].high))
            }
            if (candles[i].low < candles[i-1].low && candles[i].low < candles[i+1].low &&
                candles[i].low < candles[i-2].low && candles[i].low < candles[i+2].low) {
                lows.add(Pair(i, candles[i].low))
            }
        }

        if (highs.size < 2 || lows.size < 2) return MarketStructure.RANGING

        val lastHighs = highs.takeLast(2)
        val lastLows = lows.takeLast(2)

        val hhhl = lastHighs[1].second > lastHighs[0].second &&
                   lastLows[1].second > lastLows[0].second
        val lhll = lastHighs[1].second < lastHighs[0].second &&
                   lastLows[1].second < lastLows[0].second

        return when {
            hhhl -> MarketStructure.BULLISH    // HH + HL = tendance haussière
            lhll -> MarketStructure.BEARISH    // LH + LL = tendance baissière
            else -> MarketStructure.RANGING
        }
    }

    private fun filterBasesByStructure(
        bases: List<BasePattern>,
        structure: MarketStructure
    ): List<BasePattern> {
        return bases.filter { base ->
            when (structure) {
                MarketStructure.BULLISH -> base.signalType == SignalType.BUY   // Seulement BUY en hausse
                MarketStructure.BEARISH -> base.signalType == SignalType.SELL  // Seulement SELL en baisse
                MarketStructure.RANGING -> true  // Les deux en ranging
            }
        }.filter { it.strength >= 2 }  // Force minimum
    }

    // ============================================================
    // GÉNÉRATION DE SIGNAL
    // ============================================================

    private fun generateSignal(
        symbol: String,
        base: BasePattern,
        sniper: SniperEntry,
        currentPrice: Double,
        candles: List<Candle>
    ): TradeSignal? {
        if (!sniper.isValid) return null

        // Vérifier que le prix est encore dans ou proche de la zone
        val zoneSize = base.zoneHigh - base.zoneLow
        val priceToZone = when (base.signalType) {
            SignalType.BUY -> abs(currentPrice - base.zoneLow) / zoneSize
            SignalType.SELL -> abs(currentPrice - base.zoneHigh) / zoneSize
        }
        if (priceToZone > 0.5) return null  // Trop loin de la zone

        val pipValue = if (symbol.contains("JPY")) 0.01 else 0.0001
        val slBuffer = if (symbol.contains("XAU")) 0.5 else 3.0  // GOLD = 50 cents buffer

        val sl = calculateStopLoss(base, base.signalType, slBuffer, pipValue)
        val tp = calculateTakeProfit(sniper.entryPrice, sl, base.signalType, config.rrRatio)

        return TradeSignal(
            symbol = symbol,
            type = base.signalType,
            basePattern = base.type,
            sniperType = sniper.type,
            entryPrice = sniper.entryPrice,
            stopLoss = sl,
            takeProfit = tp,
            confidence = sniper.confidence,
            timestamp = System.currentTimeMillis(),
            reason = buildSignalReason(base, sniper)
        )
    }

    private fun buildSignalReason(base: BasePattern, sniper: SniperEntry): String {
        return "${base.type} | Zone: ${String.format("%.5f", base.zoneLow)}-${String.format("%.5f", base.zoneHigh)} | ${sniper.type} | Force: ${base.strength}/5"
    }

    // ============================================================
    // DÉTECTION DE DANGER INTERNE
    // ============================================================

    private fun detectBearishBreak(candles: List<Candle>, openPrice: Double): Boolean {
        val lows = candles.map { it.low }
        val recentLow = lows.takeLast(3).min()
        return recentLow < openPrice * 0.998
    }

    private fun detectBullishBreak(candles: List<Candle>, openPrice: Double): Boolean {
        val highs = candles.map { it.high }
        val recentHigh = highs.takeLast(3).max()
        return recentHigh > openPrice * 1.002
    }

    private fun isAccumulation(candles: List<Candle>): Boolean {
        if (candles.size < 5) return false
        val ranges = candles.map { it.high - it.low }
        val avgRange = ranges.average()
        val maxRange = ranges.max()
        // Accumulation si les bougies sont petites (range < 50% de la moyenne générale)
        return maxRange < avgRange * 0.5
    }

    // ============================================================
    // ENUMS INTERNES
    // ============================================================

    private enum class MoveType { RALLY, DROP, NEUTRAL }
    private enum class MarketStructure { BULLISH, BEARISH, RANGING }
}

// ============================================================
// CONFIGURATION
// ============================================================

data class StrategyConfig(
    val riskPercent: Double = 1.0,        // Risque par trade (1%)
    val rrRatio: Double = 2.0,             // Risk:Reward ratio (1:2)
    val maxFloatLoss: Double = 50.0,       // Float max avant coupure (USD)
    val dangerThresholdPercent: Double = 0.5, // % de mouvement adverse pour danger
    val minLotSize: Double = 0.01,
    val maxLotSize: Double = 1.0,
    val maxOpenTrades: Int = 3,            // Max positions simultanées
    val enableAutoClose: Boolean = true,   // Fermeture automatique si danger
    val dangerLevel: DangerLevel = DangerLevel.HIGH  // Niveau de danger pour coupure auto
)

enum class DangerLevel(val priority: Int) {
    NONE(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4)
}
