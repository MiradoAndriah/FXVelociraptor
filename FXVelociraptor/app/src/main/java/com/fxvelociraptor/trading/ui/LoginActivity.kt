package com.fxvelociraptor.trading.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.fxvelociraptor.trading.databinding.ActivityLoginBinding
import com.fxvelociraptor.trading.utils.MT5Config
import com.fxvelociraptor.trading.utils.PrefsManager

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: PrefsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        binding.btnContinue.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val login = binding.etLogin.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val server = binding.etServer.text.toString().trim().ifEmpty { "FBS-Real" }

            if (url.isEmpty() || login.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "⚠️ Remplissez tous les champs", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.saveMT5Config(MT5Config(url, login, password, server))
            prefs.setFirstLaunchDone()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        binding.btnSkip.setOnClickListener {
            prefs.setFirstLaunchDone()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
