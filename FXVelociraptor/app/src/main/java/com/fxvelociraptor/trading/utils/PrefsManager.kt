package com.fxvelociraptor.trading.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.fxvelociraptor.trading.model.BotConfig

data class MT5Config(
    val serverUrl: String = "",
    val login: String = "",
    val password: String = "",
    val server: String = "FBS-Real"
)

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "fxvelociraptor_prefs", Context.MODE_PRIVATE
    )
    private val gson = Gson()

    // MT5 Config
    fun saveMT5Config(config: MT5Config) {
        prefs.edit().apply {
            putString("mt5_server_url", config.serverUrl)
            putString("mt5_login", config.login)
            putString("mt5_password", config.password)  // En production: utiliser EncryptedSharedPreferences
            putString("mt5_server", config.server)
            apply()
        }
    }

    fun loadMT5Config(): MT5Config {
        return MT5Config(
            serverUrl = prefs.getString("mt5_server_url", "") ?: "",
            login = prefs.getString("mt5_login", "") ?: "",
            password = prefs.getString("mt5_password", "") ?: "",
            server = prefs.getString("mt5_server", "FBS-Real") ?: "FBS-Real"
        )
    }

    // Bot Config
    fun saveBotConfig(config: BotConfig) {
        prefs.edit().putString("bot_config", gson.toJson(config)).apply()
    }

    fun loadBotConfig(): BotConfig {
        val json = prefs.getString("bot_config", null)
        return if (json != null) {
            try { gson.fromJson(json, BotConfig::class.java) } catch (e: Exception) { BotConfig() }
        } else BotConfig()
    }

    fun isFirstLaunch(): Boolean = prefs.getBoolean("first_launch", true)
    fun setFirstLaunchDone() = prefs.edit().putBoolean("first_launch", false).apply()
}
