// ============================================================
// FXVelociraptorApp.kt
// ============================================================
package com.fxvelociraptor.trading

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class FXVelociraptorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel("trading_channel", "Bot Trading", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel("trades_channel", "Trades & Alertes", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("danger_channel", "⚠️ Danger", NotificationManager.IMPORTANCE_MAX)
        ).forEach { manager.createNotificationChannel(it) }
    }
}
