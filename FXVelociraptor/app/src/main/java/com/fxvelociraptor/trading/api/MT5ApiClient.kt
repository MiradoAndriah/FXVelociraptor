package com.fxvelociraptor.trading.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MT5 API Client
 * Connexion via MetaTrader 5 WebAPI (protocole REST + WebSocket)
 * Compatible avec: MT5 WebAPI Manager, MT5 Expert Advisor HTTP bridge
 *
 * Pour utiliser cette app, vous devez avoir:
 * 1. Un compte MT5 chez FBS (Zero Spread)
 * 2. L'EA "MT5 HTTP Bridge" installé sur votre MT5 PC/VPS
 *    (fourni dans le dossier /ea/ de ce projet)
 * 3. L'IP et port de votre MT5 configurés dans l'app
 */
class MT5ApiClient(
    private val serverUrl: String,   // ex: "ws://192.168.1.100:8080" ou votre VPS
    private val login: String,
    private val password: String,
    private val server: String       // ex: "FBS-Real" ou "FBS-Demo"
) {

    companion object {
        private const val TAG = "MT5ApiClient"
        private const val RECONNECT_DELAY = 5000L
        private const val HEARTBEAT_INTERVAL = 15000L
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val requestIdCounter = AtomicLong(1)
    private val pendingRequests = mutableMapOf<Long, CompletableDeferred<JsonObject>>()

    // State flows
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _priceUpdates = MutableStateFlow<PriceData?>(null)
    val priceUpdates: StateFlow<PriceData?> = _priceUpdates

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo

    private val _openPositions = MutableStateFlow<List<Position>>(emptyList())
    val openPositions: StateFlow<List<Position>> = _openPositions

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var heartbeatJob: Job? = null
    private var isAuthenticated = false

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, AUTHENTICATED, ERROR
    }

    // ============================================================
    // CONNEXION
    // ============================================================

    fun connect() {
        if (_connectionState.value == ConnectionState.CONNECTING ||
            _connectionState.value == ConnectionState.AUTHENTICATED) return

        _connectionState.value = ConnectionState.CONNECTING
        Log.d(TAG, "Connexion à MT5: $serverUrl")

        val request = Request.Builder()
            .url(serverUrl)
            .addHeader("User-Agent", "FXVelociraptor-Android/1.0")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket ouvert - Authentification...")
                _connectionState.value = ConnectionState.CONNECTED
                authenticate()
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Erreur WebSocket: ${t.message}")
                _connectionState.value = ConnectionState.ERROR
                isAuthenticated = false
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket fermé: $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
                isAuthenticated = false
            }
        })
    }

    private fun authenticate() {
        val authMsg = JsonObject().apply {
            addProperty("action", "auth")
            addProperty("login", login)
            addProperty("password", password)
            addProperty("server", server)
        }
        send(authMsg.toString())
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val action = json.get("action")?.asString ?: ""
            val id = json.get("id")?.asLong

            // Résoudre les requêtes en attente
            if (id != null && pendingRequests.containsKey(id)) {
                pendingRequests.remove(id)?.complete(json)
                return
            }

            when (action) {
                "auth_response" -> {
                    val success = json.get("success")?.asBoolean ?: false
                    if (success) {
                        isAuthenticated = true
                        _connectionState.value = ConnectionState.AUTHENTICATED
                        Log.d(TAG, "✅ Authentifié sur MT5")
                        // Charger les données initiales
                        scope.launch {
                            refreshAccountInfo()
                            refreshOpenPositions()
                        }
                    } else {
                        Log.e(TAG, "❌ Échec authentification MT5")
                        _connectionState.value = ConnectionState.ERROR
                    }
                }
                "tick" -> {
                    val symbol = json.get("symbol")?.asString ?: return
                    val bid = json.get("bid")?.asDouble ?: return
                    val ask = json.get("ask")?.asDouble ?: return
                    _priceUpdates.value = PriceData(symbol, bid, ask, System.currentTimeMillis())
                }
                "account_update" -> {
                    parseAccountInfo(json)?.let { _accountInfo.value = it }
                }
                "positions_update" -> {
                    _openPositions.value = parsePositions(json)
                }
                "order_result" -> {
                    val ticket = json.get("ticket")?.asLong ?: -1
                    val success = json.get("success")?.asBoolean ?: false
                    Log.d(TAG, "Résultat ordre #$ticket: $success")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur parsing message: ${e.message}")
        }
    }

    // ============================================================
    // ACTIONS TRADING
    // ============================================================

    /**
     * Ouvrir un ordre BUY (DBR, RBR selon stratégie FXVelociraptor)
     */
    suspend fun openBuy(
        symbol: String,
        lotSize: Double,
        stopLoss: Double,      // Prix exact du SL
        takeProfit: Double,    // Prix exact du TP
        comment: String = "FXVelociraptor"
    ): OrderResult {
        return sendOrder(
            symbol = symbol,
            type = "BUY",
            lotSize = lotSize,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            comment = comment
        )
    }

    /**
     * Ouvrir un ordre SELL (RBD, DBD selon stratégie FXVelociraptor)
     */
    suspend fun openSell(
        symbol: String,
        lotSize: Double,
        stopLoss: Double,
        takeProfit: Double,
        comment: String = "FXVelociraptor"
    ): OrderResult {
        return sendOrder(
            symbol = symbol,
            type = "SELL",
            lotSize = lotSize,
            stopLoss = stopLoss,
            takeProfit = takeProfit,
            comment = comment
        )
    }

    /**
     * Fermeture d'urgence d'une position (Stop Loss Manuel)
     * Utilisé quand le bot détecte un danger
     */
    suspend fun closePosition(ticket: Long): OrderResult {
        if (!isAuthenticated) return OrderResult(false, "Non connecté", -1)

        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "close_position")
            addProperty("ticket", ticket)
        }
        send(msg.toString())

        return try {
            val response = withTimeout(10000) { deferred.await() }
            val success = response.get("success")?.asBoolean ?: false
            val errorMsg = response.get("error")?.asString ?: ""
            OrderResult(success, errorMsg, response.get("ticket")?.asLong ?: -1)
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            OrderResult(false, "Timeout", -1)
        }
    }

    /**
     * Fermer TOUTES les positions ouvertes en urgence
     */
    suspend fun closeAllPositions(): Boolean {
        val positions = _openPositions.value
        var allClosed = true
        for (position in positions) {
            val result = closePosition(position.ticket)
            if (!result.success) {
                allClosed = false
                Log.e(TAG, "Impossible de fermer #${position.ticket}: ${result.errorMessage}")
            }
        }
        return allClosed
    }

    /**
     * Modifier le Stop Loss d'une position existante
     */
    suspend fun modifyStopLoss(ticket: Long, newSL: Double, newTP: Double): OrderResult {
        if (!isAuthenticated) return OrderResult(false, "Non connecté", -1)

        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "modify_position")
            addProperty("ticket", ticket)
            addProperty("sl", newSL)
            addProperty("tp", newTP)
        }
        send(msg.toString())

        return try {
            val response = withTimeout(10000) { deferred.await() }
            OrderResult(
                response.get("success")?.asBoolean ?: false,
                response.get("error")?.asString ?: "",
                ticket
            )
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            OrderResult(false, "Timeout", -1)
        }
    }

    /**
     * Abonnement aux ticks d'un symbole
     */
    fun subscribeToSymbol(symbol: String) {
        val msg = JsonObject().apply {
            addProperty("action", "subscribe")
            addProperty("symbol", symbol)
        }
        send(msg.toString())
    }

    /**
     * Récupérer les bougies historiques (pour analyse de structure)
     */
    suspend fun getCandles(
        symbol: String,
        timeframe: String,   // "M1","M5","M15","M30","H1","H4","D1"
        count: Int = 200
    ): List<Candle> {
        if (!isAuthenticated) return emptyList()

        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "get_candles")
            addProperty("symbol", symbol)
            addProperty("timeframe", timeframe)
            addProperty("count", count)
        }
        send(msg.toString())

        return try {
            val response = withTimeout(15000) { deferred.await() }
            val candlesArray = response.getAsJsonArray("candles") ?: return emptyList()
            candlesArray.map { el ->
                val c = el.asJsonObject
                Candle(
                    time = c.get("time").asLong,
                    open = c.get("open").asDouble,
                    high = c.get("high").asDouble,
                    low = c.get("low").asDouble,
                    close = c.get("close").asDouble,
                    volume = c.get("volume").asLong
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur getCandles: ${e.message}")
            emptyList()
        }
    }

    suspend fun refreshAccountInfo() {
        if (!isAuthenticated) return
        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        send(JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "get_account")
        }.toString())
        try {
            val response = withTimeout(5000) { deferred.await() }
            parseAccountInfo(response)?.let { _accountInfo.value = it }
        } catch (e: Exception) { /* ignore */ }
    }

    suspend fun refreshOpenPositions() {
        if (!isAuthenticated) return
        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        send(JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "get_positions")
        }.toString())
        try {
            val response = withTimeout(5000) { deferred.await() }
            _openPositions.value = parsePositions(response)
        } catch (e: Exception) { /* ignore */ }
    }

    // ============================================================
    // UTILITAIRES PRIVÉS
    // ============================================================

    private suspend fun sendOrder(
        symbol: String,
        type: String,
        lotSize: Double,
        stopLoss: Double,
        takeProfit: Double,
        comment: String
    ): OrderResult {
        if (!isAuthenticated) return OrderResult(false, "Non connecté à MT5", -1)

        val id = requestIdCounter.getAndIncrement()
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred

        val msg = JsonObject().apply {
            addProperty("id", id)
            addProperty("action", "open_order")
            addProperty("symbol", symbol)
            addProperty("type", type)
            addProperty("lot", lotSize)
            addProperty("sl", stopLoss)
            addProperty("tp", takeProfit)
            addProperty("comment", comment)
        }
        send(msg.toString())

        return try {
            val response = withTimeout(10000) { deferred.await() }
            val success = response.get("success")?.asBoolean ?: false
            val ticket = response.get("ticket")?.asLong ?: -1
            val error = response.get("error")?.asString ?: ""
            Log.d(TAG, "Ordre $type $symbol: success=$success ticket=$ticket")
            OrderResult(success, error, ticket)
        } catch (e: TimeoutCancellationException) {
            pendingRequests.remove(id)
            Log.e(TAG, "Timeout pour ordre $type $symbol")
            OrderResult(false, "Timeout - vérifiez votre connexion MT5", -1)
        }
    }

    private fun parseAccountInfo(json: JsonObject): AccountInfo? {
        return try {
            AccountInfo(
                login = json.get("login")?.asString ?: "",
                balance = json.get("balance")?.asDouble ?: 0.0,
                equity = json.get("equity")?.asDouble ?: 0.0,
                margin = json.get("margin")?.asDouble ?: 0.0,
                freeMargin = json.get("free_margin")?.asDouble ?: 0.0,
                profit = json.get("profit")?.asDouble ?: 0.0,
                currency = json.get("currency")?.asString ?: "USD",
                leverage = json.get("leverage")?.asInt ?: 100,
                server = json.get("server")?.asString ?: ""
            )
        } catch (e: Exception) { null }
    }

    private fun parsePositions(json: JsonObject): List<Position> {
        return try {
            val arr = json.getAsJsonArray("positions") ?: return emptyList()
            arr.map { el ->
                val p = el.asJsonObject
                Position(
                    ticket = p.get("ticket").asLong,
                    symbol = p.get("symbol").asString,
                    type = p.get("type").asString,
                    lot = p.get("lot").asDouble,
                    openPrice = p.get("open_price").asDouble,
                    currentPrice = p.get("current_price").asDouble,
                    stopLoss = p.get("sl").asDouble,
                    takeProfit = p.get("tp").asDouble,
                    profit = p.get("profit").asDouble,
                    openTime = p.get("open_time").asLong,
                    comment = p.get("comment")?.asString ?: ""
                )
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun send(message: String) {
        webSocket?.send(message) ?: Log.w(TAG, "WebSocket non connecté")
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL)
                send(JsonObject().apply { addProperty("action", "ping") }.toString())
            }
        }
    }

    private fun scheduleReconnect() {
        scope.launch {
            delay(RECONNECT_DELAY)
            Log.d(TAG, "Reconnexion MT5...")
            connect()
        }
    }

    fun disconnect() {
        heartbeatJob?.cancel()
        webSocket?.close(1000, "Déconnexion normale")
        _connectionState.value = ConnectionState.DISCONNECTED
        isAuthenticated = false
    }
}

// ============================================================
// MODÈLES DE DONNÉES
// ============================================================

data class OrderResult(
    val success: Boolean,
    val errorMessage: String,
    val ticket: Long
)

data class PriceData(
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val timestamp: Long
)

data class AccountInfo(
    val login: String,
    val balance: Double,
    val equity: Double,
    val margin: Double,
    val freeMargin: Double,
    val profit: Double,
    val currency: String,
    val leverage: Int,
    val server: String
)

data class Position(
    val ticket: Long,
    val symbol: String,
    val type: String,      // "BUY" ou "SELL"
    val lot: Double,
    val openPrice: Double,
    val currentPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val profit: Double,
    val openTime: Long,
    val comment: String
)

data class Candle(
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long
) {
    // Corps de la bougie
    val body: Double get() = Math.abs(close - open)
    val upperWick: Double get() = high - maxOf(open, close)
    val lowerWick: Double get() = minOf(open, close) - low
    val isBullish: Boolean get() = close > open
    val isBearish: Boolean get() = close < open
}
