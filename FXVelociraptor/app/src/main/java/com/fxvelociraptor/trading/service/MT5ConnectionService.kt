package com.fxvelociraptor.trading.service

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.fxvelociraptor.trading.api.MT5ApiClient
import com.fxvelociraptor.trading.ui.MainActivity
import com.fxvelociraptor.trading.utils.PrefsManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Service de connexion MT5 dédié
 * Maintient la connexion WebSocket active en arrière-plan
 */
class MT5ConnectionService : LifecycleService() {

    private lateinit var prefs: PrefsManager
    var mt5Client: MT5ApiClient? = null
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = PrefsManager(this)
    }

    fun connect(onConnected: () -> Unit, onError: (String) -> Unit) {
        val config = prefs.loadMT5Config()
        mt5Client = MT5ApiClient(
            serverUrl = config.serverUrl,
            login = config.login,
            password = config.password,
            server = config.server
        )

        lifecycleScope.launch {
            mt5Client!!.connectionState.collectLatest { state ->
                when (state) {
                    MT5ApiClient.ConnectionState.AUTHENTICATED -> onConnected()
                    MT5ApiClient.ConnectionState.ERROR -> onError("Connexion MT5 perdue")
                    else -> {}
                }
            }
        }

        mt5Client!!.connect()
    }

    fun disconnect() {
        mt5Client?.disconnect()
        mt5Client = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
