package com.bifrost.twp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.bifrost.twp.R
import com.bifrost.twp.core.BifrostBridgeService
import com.bifrost.twp.data.ProxyRepository
import com.bifrost.twp.ui.components.MainScreen
import com.bifrost.twp.ui.theme.BifrostTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: ProxyRepository

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // Notification permission granted/denied
        }

    override fun attachBaseContext(newBase: android.content.Context) {
        try {
            val prefs = newBase.getSharedPreferences("bifrost_preferences", android.content.Context.MODE_PRIVATE)
            val lang = prefs.getString("key_app_language", "en") ?: "en"
            val locale = java.util.Locale(lang)
            java.util.Locale.setDefault(locale)
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            config.setLayoutDirection(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } catch (_: Exception) {
            super.attachBaseContext(newBase)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = ProxyRepository.getInstance(applicationContext)

        // Request POST_NOTIFICATIONS on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        handleIntent(intent)

        setContent {
            val appLanguage by repository.appLanguageFlow.collectAsState()
            BifrostTheme(language = appLanguage) {
                MainScreen(
                    repository = repository,
                    onLaunchQrScanner = {
                        val intent = Intent(this, QrScannerActivity::class.java)
                        startActivity(intent)
                    }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val isServiceRunning = BifrostBridgeService.serviceRunning.value
        val isBridgeEnabled = repository.isBridgeEnabledFlow.value
        val isRunOnStartup = repository.runOnStartupFlow.value
        val hasActiveProxy = repository.activeProxyFlow.value != null

        if (isBridgeEnabled && isRunOnStartup && hasActiveProxy) {
            if (!isServiceRunning) {
                repository.setBridgeEnabled(true)
                BifrostBridgeService.start(this)
            }
        } else if (!isServiceRunning) {
            if (!isBridgeEnabled || !isRunOnStartup || !hasActiveProxy) {
                repository.setBridgeEnabled(false)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val raw = intent?.dataString ?: intent?.data?.toString() ?: intent?.getStringExtra(Intent.EXTRA_TEXT)
        if (!raw.isNullOrBlank()) {
            val config = com.bifrost.twp.model.TwpLinkParser.parseLink(raw)
            if (config != null) {
                repository.setBridgeEnabled(true)
                repository.addOrUpdateProxy(config, makeActive = true)
                com.bifrost.twp.core.BifrostBridgeService.start(this)
                val localPort = repository.localPortFlow.value
                com.bifrost.twp.util.TelegramLauncher.openTelegramSocks(this, localPort)
                android.widget.Toast.makeText(this, getString(R.string.toast_proxy_activated_named, config.name), android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
