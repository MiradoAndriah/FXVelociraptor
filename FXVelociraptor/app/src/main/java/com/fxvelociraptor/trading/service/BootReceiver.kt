package com.fxvelociraptor.trading.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.fxvelociraptor.trading.utils.PrefsManager

/**
 * Redémarre le bot automatiquement après un redémarrage du téléphone
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            val config = prefs.loadBotConfig()
            if (config.isActive) {
                val serviceIntent = Intent(context, TradingBotService::class.java).apply {
                    action = TradingBotService.ACTION_START
                }
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
