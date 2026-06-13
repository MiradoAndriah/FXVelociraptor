package com.fxvelociraptor.trading.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fxvelociraptor.trading.databinding.ActivityMainBinding
import com.fxvelociraptor.trading.service.TradingBotService
import com.fxvelociraptor.trading.utils.PrefsManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        setupUI()
        observeBot()
    }

    private fun setupUI() {
        // Bouton Start/Stop Bot
        binding.btnToggleBot.setOnClickListener {
            val isRunning = TradingBotService.botState.value == TradingBotService.BotState.RUNNING
            if (isRunning) {
                stopBot()
            } else {
                // Vérifier la config MT5
                val mt5Config = prefs.loadMT5Config()
                if (mt5Config.serverUrl.isEmpty() || mt5Config.login.isEmpty()) {
                    Toast.makeText(this, "⚠️ Configurez d'abord votre connexion MT5", Toast.LENGTH_LONG).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }
                startBot()
            }
        }

        // Bouton URGENCE - Fermer tout
        binding.btnEmergencyClose.setOnClickListener {
            showEmergencyDialog()
        }

        // Navigation
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnHistory.setOnClickListener {
            startActivity(Intent(this, TradeHistoryActivity::class.java))
        }
    }

    private fun observeBot() {
        // État du bot
        lifecycleScope.launch {
            TradingBotService.botState.collectLatest { state ->
                updateBotStateUI(state)
            }
        }

        // Dernière alerte danger
        lifecycleScope.launch {
            TradingBotService.lastDangerAlert.collectLatest { alert ->
                if (alert != null) {
                    binding.tvDangerAlert.visibility = View.VISIBLE
                    binding.tvDangerAlert.text = "⚠️ $alert"
                } else {
                    binding.tvDangerAlert.visibility = View.GONE
                }
            }
        }

        // Logs du bot
        lifecycleScope.launch {
            TradingBotService.botLogs.collectLatest { logs ->
                val logText = logs.take(20).joinToString("\n")
                binding.tvLogs.text = logText
            }
        }

        // Dernier signal
        lifecycleScope.launch {
            TradingBotService.lastSignal.collectLatest { signal ->
                if (signal != null) {
                    binding.cardLastSignal.visibility = View.VISIBLE
                    binding.tvSignalSymbol.text = "${signal.type} ${signal.symbol}"
                    binding.tvSignalPattern.text = "${signal.basePattern} | ${signal.sniperType}"
                    binding.tvSignalConfidence.text = "${signal.confidence}%"
                    binding.tvSignalEntry.text = String.format("%.5f", signal.entryPrice)
                    binding.tvSignalSL.text = String.format("%.5f", signal.stopLoss)
                    binding.tvSignalTP.text = String.format("%.5f", signal.takeProfit)
                    binding.tvSignalRR.text = "1:${String.format("%.1f", signal.riskReward)}"
                }
            }
        }
    }

    private fun updateBotStateUI(state: TradingBotService.BotState) {
        when (state) {
            TradingBotService.BotState.RUNNING -> {
                binding.btnToggleBot.text = "⏹ ARRÊTER LE BOT"
                binding.btnToggleBot.setBackgroundColor(getColor(android.R.color.holo_red_dark))
                binding.tvBotStatus.text = "🟢 BOT ACTIF"
                binding.tvBotStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                binding.progressConnecting.visibility = View.GONE
                binding.btnEmergencyClose.isEnabled = true
            }
            TradingBotService.BotState.CONNECTING -> {
                binding.btnToggleBot.text = "⏳ CONNEXION..."
                binding.btnToggleBot.isEnabled = false
                binding.tvBotStatus.text = "🔄 Connexion MT5..."
                binding.progressConnecting.visibility = View.VISIBLE
                binding.btnEmergencyClose.isEnabled = false
            }
            TradingBotService.BotState.ERROR -> {
                binding.btnToggleBot.text = "🔄 RECONNECTER"
                binding.btnToggleBot.isEnabled = true
                binding.btnToggleBot.setBackgroundColor(getColor(android.R.color.holo_orange_dark))
                binding.tvBotStatus.text = "🔴 ERREUR - Reconnexion..."
                binding.tvBotStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                binding.progressConnecting.visibility = View.GONE
            }
            TradingBotService.BotState.STOPPED -> {
                binding.btnToggleBot.text = "▶ DÉMARRER LE BOT"
                binding.btnToggleBot.isEnabled = true
                binding.btnToggleBot.setBackgroundColor(getColor(android.R.color.holo_green_dark))
                binding.tvBotStatus.text = "⚫ BOT ARRÊTÉ"
                binding.tvBotStatus.setTextColor(getColor(android.R.color.darker_gray))
                binding.progressConnecting.visibility = View.GONE
                binding.btnEmergencyClose.isEnabled = false
            }
            TradingBotService.BotState.PAUSED -> {
                binding.tvBotStatus.text = "🟡 BOT EN PAUSE"
            }
        }
    }

    private fun startBot() {
        val intent = Intent(this, TradingBotService::class.java).apply {
            action = TradingBotService.ACTION_START
        }
        startForegroundService(intent)
        binding.btnToggleBot.isEnabled = false
    }

    private fun stopBot() {
        val intent = Intent(this, TradingBotService::class.java).apply {
            action = TradingBotService.ACTION_STOP
        }
        startService(intent)
    }

    private fun showEmergencyDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🚨 FERMETURE D'URGENCE")
            .setMessage("Fermer TOUTES les positions ouvertes immédiatement?\n\nCette action est IRRÉVERSIBLE.")
            .setPositiveButton("OUI, FERMER TOUT") { _, _ ->
                val intent = Intent(this, TradingBotService::class.java).apply {
                    action = TradingBotService.ACTION_EMERGENCY_CLOSE
                }
                startService(intent)
                Toast.makeText(this, "🚨 Fermeture d'urgence lancée", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("ANNULER", null)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show()
    }
}
