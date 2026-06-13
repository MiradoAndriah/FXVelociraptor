package com.fxvelociraptor.trading.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fxvelociraptor.trading.api.MT5ApiClient
import com.fxvelociraptor.trading.model.*
import com.fxvelociraptor.trading.strategy.DangerLevel
import com.fxvelociraptor.trading.strategy.FXVelociraptorStrategy
import com.fxvelociraptor.trading.strategy.StrategyConfig
import com.fxvelociraptor.trading.ui.MainActivity
import com.fxvelociraptor.trading.utils.PrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service principal du Bot de Trading FXVelociraptor
 * Tourne en arrière-plan, analyse les marchés, ouvre/ferme les ordres
 */
class TradingBotService : LifecycleService() {

    companion object {
        private const val TAG = "TradingBotService"
        const val CHANNEL_ID = "trading_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START_BOT"
        const val ACTION_STOP = "STOP_BOT"
        const val ACTION_EMERGENCY_CLOSE = "EMERGENCY_CLOSE"

        // État partagé (accessible depuis l'UI)
        val botState = MutableStateFlow(BotState.STOPPED)
        val lastSignal = MutableStateFlow<TradeSignal?>(null)
        val lastDangerAlert = MutableStateFlow<String?>(null)
        val botLogs = MutableStateFlow<List<String>>(emptyList())
    }

    private lateinit var prefs: PrefsManager
    private var mt5Client: MT5ApiClient? = null
    private var strategy: FXVelociraptorStrategy? = null
    private var scanJob: Job? = null
    private var dangerMonitorJob: Job? = null
    private var config: BotConfig = BotConfig()

    enum class BotState { STOPPED, CONNECTING, RUNNING, ERROR, PAUSED }

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
        createNotificationChannel()
        log("🤖 Service FXVelociraptor initialisé")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> startBot()
            ACTION_STOP -> stopBot()
            ACTION_EMERGENCY_CLOSE -> emergencyCloseAll()
        }

        return START_STICKY  // Le service redémarre si tué
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ============================================================
    // DÉMARRAGE DU BOT
    // ============================================================

    private fun startBot() {
        if (botState.value == BotState.RUNNING) return

        log("🚀 Démarrage du bot...")
        botState.value = BotState.CONNECTING

        // Lancer en foreground (notification persistante)
        startForeground(NOTIFICATION_ID, buildNotification("Connexion à MT5..."))

        // Charger la configuration
        config = prefs.loadBotConfig()
        strategy = FXVelociraptorStrategy(
            StrategyConfig(
                riskPercent = config.riskPercent,
                rrRatio = config.rrRatio,
                maxFloatLoss = config.maxFloatLoss,
                maxOpenTrades = config.maxOpenTrades,
                enableAutoClose = config.enableAutoClose
            )
        )

        // Créer le client MT5
        val mt5Config = prefs.loadMT5Config()
        mt5Client = MT5ApiClient(
            serverUrl = mt5Config.serverUrl,
            login = mt5Config.login,
            password = mt5Config.password,
            server = mt5Config.server
        )

        lifecycleScope.launch {
            // Observer la connexion MT5
            mt5Client!!.connectionState.collect { state ->
                when (state) {
                    MT5ApiClient.ConnectionState.AUTHENTICATED -> {
                        log("✅ Connecté à MT5 - Bot actif!")
                        botState.value = BotState.RUNNING
                        updateNotification("🟢 Bot actif - Surveillance en cours")
                        startScanning()
                        startDangerMonitor()
                        // S'abonner aux symboles
                        config.symbols.forEach { symbol ->
                            mt5Client!!.subscribeToSymbol(symbol)
                        }
                    }
                    MT5ApiClient.ConnectionState.ERROR -> {
                        log("❌ Erreur connexion MT5 - Reconnexion...")
                        botState.value = BotState.ERROR
                        updateNotification("🔴 Erreur - Reconnexion...")
                    }
                    MT5ApiClient.ConnectionState.DISCONNECTED -> {
                        if (botState.value == BotState.RUNNING) {
                            log("⚠️ MT5 déconnecté - En attente...")
                            botState.value = BotState.ERROR
                        }
                    }
                    else -> {}
                }
            }
        }

        // Se connecter
        mt5Client!!.connect()
    }

    // ============================================================
    // SCAN DU MARCHÉ
    // ============================================================

    private fun startScanning() {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch {
            log("🔍 Démarrage scan marché (intervalle: ${config.scanIntervalSeconds}s)")

            while (isActive && botState.value == BotState.RUNNING) {
                try {
                    val client = mt5Client ?: break
                    val openPositions = client.openPositions.value

                    // Vérifier si on peut encore ouvrir des trades
                    if (openPositions.size < config.maxOpenTrades) {
                        // Scanner chaque symbole
                        for (symbol in config.symbols) {
                            if (!isActive) break
                            scanSymbol(symbol, client)
                        }
                    } else {
                        log("📊 Max positions atteint (${openPositions.size}/${config.maxOpenTrades})")
                    }

                    // Rafraîchir les données du compte
                    client.refreshAccountInfo()
                    client.refreshOpenPositions()

                } catch (e: Exception) {
                    log("❌ Erreur scan: ${e.message}")
                }

                delay(config.scanIntervalSeconds * 1000L)
            }
        }
    }

    private suspend fun scanSymbol(symbol: String, client: MT5ApiClient) {
        log("🔍 Analyse $symbol (${config.timeframe})...")

        // Récupérer les bougies
        val candles = client.getCandles(symbol, config.timeframe, 200)
        if (candles.isEmpty()) {
            log("⚠️ Aucune bougie pour $symbol")
            return
        }

        // Analyser avec la stratégie FXVelociraptor
        val signal = strategy?.analyze(candles, symbol)

        if (signal != null && signal.confidence >= config.minConfidence) {
            // Vérifier le type de Sniper Entry activé
            val sniperEnabled = when (signal.sniperType) {
                "ST1" -> config.enableST1
                "ST2" -> config.enableST2
                "ST3" -> config.enableST3
                "ST4" -> config.enableST4
                else -> false
            }

            if (!sniperEnabled) {
                log("⏭️ $symbol: Signal ${signal.sniperType} désactivé dans config")
                return
            }

            log("🎯 SIGNAL DÉTECTÉ: ${signal.type} $symbol | ${signal.basePattern} | ${signal.sniperType} | Conf: ${signal.confidence}%")
            lastSignal.value = signal

            // Exécuter le trade
            executeSignal(signal, client)
        } else {
            log("➡️ $symbol: Pas de signal (${signal?.confidence ?: 0}% conf)")
        }
    }

    private suspend fun executeSignal(signal: TradeSignal, client: MT5ApiClient) {
        val account = client.accountInfo.value ?: run {
            log("❌ Info compte non disponible")
            return
        }

        // Calculer la taille de lot
        val lotSize = strategy?.calculateLotSize(
            accountBalance = account.balance,
            riskPercent = config.riskPercent,
            entryPrice = signal.entryPrice,
            stopLoss = signal.stopLoss,
            symbol = signal.symbol
        ) ?: 0.01

        log("📋 Ordre: ${signal.type} ${signal.symbol} | Lot: $lotSize | Entry: ${signal.entryPrice} | SL: ${signal.stopLoss} | TP: ${signal.takeProfit}")

        val result = when (signal.type) {
            SignalType.BUY -> client.openBuy(
                symbol = signal.symbol,
                lotSize = lotSize,
                stopLoss = signal.stopLoss,
                takeProfit = signal.takeProfit,
                comment = "FXV_${signal.basePattern}_${signal.sniperType}"
            )
            SignalType.SELL -> client.openSell(
                symbol = signal.symbol,
                lotSize = lotSize,
                stopLoss = signal.stopLoss,
                takeProfit = signal.takeProfit,
                comment = "FXV_${signal.basePattern}_${signal.sniperType}"
            )
        }

        if (result.success) {
            log("✅ Ordre exécuté! Ticket #${result.ticket}")
            sendTradeNotification(signal, result.ticket, lotSize)
            updateNotification("🟢 Trade ouvert: ${signal.type} ${signal.symbol}")
        } else {
            log("❌ Échec ordre: ${result.errorMessage}")
        }
    }

    // ============================================================
    // MONITEUR DE DANGER - Coupe le SL automatiquement
    // ============================================================

    private fun startDangerMonitor() {
        dangerMonitorJob?.cancel()
        dangerMonitorJob = lifecycleScope.launch {
            log("🛡️ Moniteur de danger actif")

            while (isActive && botState.value == BotState.RUNNING) {
                try {
                    val client = mt5Client ?: break
                    val positions = client.openPositions.value

                    for (position in positions) {
                        // Seulement surveiller les positions FXVelociraptor
                        if (!position.comment.startsWith("FXV_")) continue

                        // Récupérer les bougies récentes pour analyse
                        val candles = client.getCandles(position.symbol, "M15", 50)
                        if (candles.isEmpty()) continue

                        // Analyser le danger
                        val dangerLevel = strategy?.isDangerDetected(
                            candles = candles,
                            positionType = position.type,
                            openPrice = position.openPrice,
                            currentProfit = position.profit,
                            stopLoss = position.stopLoss
                        ) ?: DangerLevel.NONE

                        when (dangerLevel) {
                            DangerLevel.CRITICAL -> {
                                log("🚨 DANGER CRITIQUE: #${position.ticket} ${position.symbol} - FERMETURE IMMÉDIATE!")
                                lastDangerAlert.value = "DANGER CRITIQUE: ${position.symbol} fermé!"
                                val result = client.closePosition(position.ticket)
                                if (result.success) {
                                    log("✅ Position #${position.ticket} fermée (DANGER CRITIQUE)")
                                    sendDangerNotification(position.symbol, "CRITIQUE - Position fermée", position.profit)
                                }
                            }
                            DangerLevel.HIGH -> {
                                if (config.enableAutoClose) {
                                    log("⚠️ DANGER ÉLEVÉ: #${position.ticket} ${position.symbol} - Fermeture auto")
                                    lastDangerAlert.value = "DANGER ÉLEVÉ: ${position.symbol} fermé!"
                                    client.closePosition(position.ticket)
                                    sendDangerNotification(position.symbol, "ÉLEVÉ - Position fermée", position.profit)
                                }
                            }
                            DangerLevel.MEDIUM -> {
                                log("⚠️ Danger moyen: ${position.symbol} - Surveillance renforcée")
                                lastDangerAlert.value = "Attention: ${position.symbol} sous pression"
                                sendDangerNotification(position.symbol, "MOYEN - Surveiller", position.profit)
                            }
                            DangerLevel.LOW -> {
                                // Accumulation - ignorer selon règle FXVelociraptor
                                log("ℹ️ ${position.symbol}: Accumulation détectée - Maintien position")
                            }
                            DangerLevel.NONE -> {} // Tout va bien
                        }
                    }
                } catch (e: Exception) {
                    log("❌ Erreur moniteur: ${e.message}")
                }

                delay(10_000) // Vérification toutes les 10 secondes
            }
        }
    }

    // ============================================================
    // FERMETURE D'URGENCE
    // ============================================================

    private fun emergencyCloseAll() {
        lifecycleScope.launch {
            log("🚨 FERMETURE D'URGENCE DE TOUTES LES POSITIONS!")
            val result = mt5Client?.closeAllPositions() ?: false
            if (result) {
                log("✅ Toutes les positions fermées")
            } else {
                log("❌ Erreur lors de la fermeture d'urgence")
            }
        }
    }

    // ============================================================
    // ARRÊT DU BOT
    // ============================================================

    private fun stopBot() {
        log("⏹️ Arrêt du bot...")
        scanJob?.cancel()
        dangerMonitorJob?.cancel()
        mt5Client?.disconnect()
        mt5Client = null
        botState.value = BotState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBot()
    }

    // ============================================================
    // NOTIFICATIONS
    // ============================================================

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "FX Velociraptor Trading",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications du bot de trading"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FX Velociraptor Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun sendTradeNotification(signal: TradeSignal, ticket: Long, lot: Double) {
        val channel = NotificationChannel(
            "trades_channel", "Trades",
            NotificationManager.IMPORTANCE_HIGH
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        val notif = NotificationCompat.Builder(this, "trades_channel")
            .setContentTitle("🎯 Nouveau Trade FXVelociraptor")
            .setContentText("${signal.type} ${signal.symbol} | #${ticket} | Lot: $lot")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("${signal.type} ${signal.symbol}\n" +
                        "Pattern: ${signal.basePattern} | Entrée: ${signal.sniperType}\n" +
                        "Entry: ${String.format("%.5f", signal.entryPrice)}\n" +
                        "SL: ${String.format("%.5f", signal.stopLoss)}\n" +
                        "TP: ${String.format("%.5f", signal.takeProfit)}\n" +
                        "RR: 1:${String.format("%.1f", signal.riskReward)}")
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(ticket.toInt(), notif)
    }

    private fun sendDangerNotification(symbol: String, level: String, profit: Double) {
        val manager = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, "trades_channel")
            .setContentTitle("⚠️ DANGER DÉTECTÉ - $symbol")
            .setContentText("Niveau: $level | P&L: ${String.format("%.2f", profit)}")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .build()
        manager.notify(symbol.hashCode(), notif)
    }

    // ============================================================
    // LOGGING
    // ============================================================

    private fun log(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        val currentLogs = botLogs.value.toMutableList()
        currentLogs.add(0, logEntry)
        if (currentLogs.size > 100) currentLogs.removeAt(currentLogs.size - 1)
        botLogs.value = currentLogs
    }
}
