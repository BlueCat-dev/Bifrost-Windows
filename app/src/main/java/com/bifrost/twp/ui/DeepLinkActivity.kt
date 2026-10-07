package com.bifrost.twp.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.bifrost.twp.R
import com.bifrost.twp.core.BifrostBridgeService
import com.bifrost.twp.data.ProxyRepository
import com.bifrost.twp.model.TwpLinkParser
import com.bifrost.twp.util.TelegramLauncher

/**
 * Zero-Click Deep Link Handler for twp:// links and shared configurations.
 *
 * Automatically saves and activates the incoming configuration in the background,
 * starts the local SOCKS5 bridge, and seamlessly forwards to Telegram's native
 * proxy setup dialog (https://t.me/socks?server=127.0.0.1&port=5050).
 */
class DeepLinkActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(incomingIntent: Intent?) {
        val rawData = incomingIntent?.dataString
            ?: incomingIntent?.data?.toString()
            ?: incomingIntent?.getStringExtra(Intent.EXTRA_TEXT)

        if (!rawData.isNullOrBlank()) {
            val repository = ProxyRepository.getInstance(applicationContext)
            val parsedConfig = TwpLinkParser.parseLink(rawData)

            if (parsedConfig != null) {
                // 1. Enable bridge state in repository
                repository.setBridgeEnabled(true)

                // 2. Save and activate proxy configuration immediately
                repository.addOrUpdateProxy(parsedConfig, makeActive = true)

                // 3. Start the local SOCKS5 bridge foreground service
                BifrostBridgeService.start(applicationContext)

                // 4. Forward directly to Telegram using applicationContext
                val localPort = repository.localPortFlow.value
                TelegramLauncher.openTelegramSocks(applicationContext, localPort)

                Toast.makeText(
                    applicationContext,
                    getString(R.string.toast_proxy_activated_named, parsedConfig.name),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(applicationContext, getString(R.string.msg_invalid_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        // Delay finish slightly to guarantee the Telegram task intent is committed by the OS ActivityTaskManager
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
            overridePendingTransition(0, 0)
        }, 200)
    }
}
