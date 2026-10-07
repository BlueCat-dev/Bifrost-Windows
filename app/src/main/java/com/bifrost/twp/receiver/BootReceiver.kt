package com.bifrost.twp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.bifrost.twp.core.BifrostBridgeService
import com.bifrost.twp.data.ProxyRepository

/**
 * Automatically starts the Bifrost bridge upon device reboot
 * if an active proxy configuration exists and "Run on Startup" is enabled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val isBoot = action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
                action == "android.intent.action.REBOOT"

        val isUnlock = action == Intent.ACTION_USER_PRESENT

        if (!isBoot && !isUnlock) return

        // Fast in-memory check for unlock events
        if (isUnlock && hasHandledBoot) return

        try {
            // Guard: Ensure user storage is decrypted before reading preferences (Direct Boot)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                if (userManager != null && !userManager.isUserUnlocked) {
                    return
                }
            }

            // Persisted check: If it's a screen unlock and boot was already handled, ignore it
            if (isUnlock && isBootAlreadyHandled(context)) return

            val appContext = context.applicationContext ?: context
            val repository = ProxyRepository.getInstance(appContext)
            val isBridgeEnabled = repository.isBridgeEnabledFlow.value
            val isRunOnStartup = repository.runOnStartupFlow.value
            val hasActiveProxy = repository.activeProxyFlow.value != null

            // On screen unlock: do not start if user manually stopped the proxy
            if (isUnlock && !isBridgeEnabled) {
                markBootHandled(appContext)
                return
            }

            markBootHandled(appContext)

            if (isRunOnStartup && hasActiveProxy) {
                if (!BifrostBridgeService.serviceRunning.value) {
                    repository.setBridgeEnabled(true)
                    BifrostBridgeService.start(appContext)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val PREFS_NAME = "bifrost_preferences"
        private const val KEY_LAST_HANDLED_BOOT_COUNT = "key_last_handled_boot_count"
        private const val KEY_LAST_HANDLED_BOOT_TIME = "key_last_handled_boot_time"

        @Volatile
        private var hasHandledBoot = false

        private fun isBootAlreadyHandled(context: Context): Boolean {
            if (hasHandledBoot) return true
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // 1. Check system BOOT_COUNT (API 24+)
                val currentBootCount = getBootCount(context)
                val savedBootCount = prefs.getInt(KEY_LAST_HANDLED_BOOT_COUNT, -1)
                if (currentBootCount > 0 && savedBootCount > 0) {
                    val handled = (savedBootCount == currentBootCount)
                    if (handled) hasHandledBoot = true
                    return handled
                }

                // 2. Fallback: Check estimated boot time (monotonic elapsed realtime vs wall clock)
                val savedBootTimeMs = prefs.getLong(KEY_LAST_HANDLED_BOOT_TIME, 0L)
                if (savedBootTimeMs > 0L) {
                    val currentBootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                    if (Math.abs(currentBootTimeMs - savedBootTimeMs) < 60_000L) {
                        hasHandledBoot = true
                        return true
                    }
                }

                false
            } catch (_: Exception) {
                false
            }
        }

        private fun markBootHandled(context: Context) {
            hasHandledBoot = true
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val editor = prefs.edit()

                val currentBootCount = getBootCount(context)
                if (currentBootCount > 0) {
                    editor.putInt(KEY_LAST_HANDLED_BOOT_COUNT, currentBootCount)
                }

                val currentBootTimeMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
                editor.putLong(KEY_LAST_HANDLED_BOOT_TIME, currentBootTimeMs)

                editor.apply()
            } catch (_: Exception) {}
        }

        private fun getBootCount(context: Context): Int {
            return try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            } catch (_: Exception) {
                -1
            }
        }
    }
}
