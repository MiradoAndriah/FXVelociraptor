package com.fxvelociraptor.trading.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.fxvelociraptor.trading.databinding.ActivitySettingsBinding
import com.fxvelociraptor.trading.utils.MT5Config
import com.fxvelociraptor.trading.utils.PrefsManager
import com.fxvelociraptor.trading.model.BotConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        supportActionBar?.apply {
            title = "⚙️ Configuration"
            setDisplayHomeAsUpEnabled(true)
        }

        loadCurrentConfig()
        setupSaveButton()
        setupTestButton()
    }

    private fun loadCurrentConfig() {
        val mt5 = prefs.loadMT5Config()
        val bot = prefs.loadBotConfig()
        binding.etServerUrl.setText(mt5.serverUrl)
        binding.etLogin.setText(mt5.login)
        binding.etServer.setText(mt5.server)
        binding.etRiskPercent.setText(bot.riskPercent.toString())
        binding.etRrRatio.setText(bot.rrRatio.toString())
        binding.etMaxFloat.setText(bot.maxFloatLoss.toString())
        binding.etMaxTrades.setText(bot.maxOpenTrades.toString())
        binding.switchAutoClose.isChecked = bot.enableAutoClose
        binding.switchNotifs.isChecked = bot.enableNotifications
        binding.etSymbols.setText(bot.symbols.joinToString(","))
        binding.etTimeframe.setText(bot.timeframe)
        binding.etScanInterval.setText(bot.scanIntervalSeconds.toString())
        binding.switchST1.isChecked = bot.enableST1
        binding.switchST2.isChecked = bot.enableST2
        binding.switchST3.isChecked = bot.enableST3
        binding.switchST4.isChecked = bot.enableST4
    }

    private fun setupSaveButton() {
        binding.btnSave.setOnClickListener {
            try {
                val password = binding.etPassword.text.toString()
                prefs.saveMT5Config(MT5Config(
                    serverUrl = binding.etServerUrl.text.toString().trim(),
                    login = binding.etLogin.text.toString().trim(),
                    password = if (password.isNotEmpty()) password else prefs.loadMT5Config().password,
                    server = binding.etServer.text.toString().trim()
                ))
                val symbols = binding.etSymbols.text.toString()
                    .split(",").map { it.trim() }.filter { it.isNotEmpty() }
                prefs.saveBotConfig(BotConfig(
                    symbols = symbols,
                    timeframe = binding.etTimeframe.text.toString().trim().uppercase(),
                    riskPercent = binding.etRiskPercent.text.toString().toDoubleOrNull() ?: 1.0,
                    rrRatio = binding.etRrRatio.text.toString().toDoubleOrNull() ?: 2.0,
                    maxFloatLoss = binding.etMaxFloat.text.toString().toDoubleOrNull() ?: 50.0,
                    maxOpenTrades = binding.etMaxTrades.text.toString().toIntOrNull() ?: 3,
                    enableAutoClose = binding.switchAutoClose.isChecked,
                    enableNotifications = binding.switchNotifs.isChecked,
                    scanIntervalSeconds = binding.etScanInterval.text.toString().toIntOrNull() ?: 30,
                    enableST1 = binding.switchST1.isChecked,
                    enableST2 = binding.switchST2.isChecked,
                    enableST3 = binding.switchST3.isChecked,
                    enableST4 = binding.switchST4.isChecked
                ))
                Toast.makeText(this, "✅ Configuration sauvegardée!", Toast.LENGTH_SHORT).show()
                finish()
            } catch (e: Exception) {
                Toast.makeText(this, "❌ Erreur: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setupTestButton() {
        binding.btnTestConnection.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val login = binding.etLogin.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val server = binding.etServer.text.toString().trim()

            if (url.isEmpty() || login.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "⚠️ Remplissez l'URL, login et mot de passe", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnTestConnection.text = "⏳ Test en cours..."
            binding.btnTestConnection.isEnabled = false

            val testClient = com.fxvelociraptor.trading.api.MT5ApiClient(url, login, password, server)
            testClient.connect()

            lifecycleScope.launch {
                try {
                    withTimeout(10000) {
                        testClient.connectionState.collect { state ->
                            when (state) {
                                com.fxvelociraptor.trading.api.MT5ApiClient.ConnectionState.AUTHENTICATED -> {
                                    runOnUiThread {
                                        Toast.makeText(this@SettingsActivity, "✅ Connexion MT5 réussie!", Toast.LENGTH_LONG).show()
                                        binding.btnTestConnection.text = "✅ CONNEXION OK"
                                        binding.btnTestConnection.isEnabled = true
                                    }
                                    testClient.disconnect()
                                    return@collect
                                }
                                com.fxvelociraptor.trading.api.MT5ApiClient.ConnectionState.ERROR -> {
                                    runOnUiThread {
                                        Toast.makeText(this@SettingsActivity, "❌ Échec connexion", Toast.LENGTH_LONG).show()
                                        binding.btnTestConnection.text = "🔄 TESTER LA CONNEXION"
                                        binding.btnTestConnection.isEnabled = true
                                    }
                                    testClient.disconnect()
                                    return@collect
                                }
                                else -> {}
                            }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    runOnUiThread {
                        Toast.makeText(this@SettingsActivity, "⏱️ Timeout - Vérifiez l'IP et le port", Toast.LENGTH_LONG).show()
                        binding.btnTestConnection.text = "🔄 TESTER LA CONNEXION"
                        binding.btnTestConnection.isEnabled = true
                    }
                    testClient.disconnect()
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressed(); return true }
}
