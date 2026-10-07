package com.bifrost.twp.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.bifrost.twp.R
import com.bifrost.twp.data.ProxyRepository
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class BridgeState {
    STOPPED,
    LISTENING,
    STREAMING
}

/**
 * Android Foreground Service maintaining the local SOCKS5 bridge.
 * Ultra-lightweight and battery-efficient.
 */
class BifrostBridgeService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: ProxyRepository
    private var socks5Server: Socks5Server? = null
    private var currentPort: Int = ProxyRepository.DEFAULT_LOCAL_PORT
    private var currentActiveProxy: ProxyConfig? = null

    override fun onCreate() {
        super.onCreate()
        repository = ProxyRepository.getInstance(this)
        createNotificationChannel()

        // Observe local port changes
        serviceScope.launch {
            repository.localPortFlow.collectLatest { newPort ->
                if (newPort != currentPort && _serviceRunning.value) {
                    currentPort = newPort
                    restartServer()
                } else {
                    currentPort = newPort
                }
            }
        }

        // Observe active proxy changes
        serviceScope.launch {
            repository.activeProxyFlow.collectLatest { newConfig ->
                currentActiveProxy = newConfig
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopBridge()
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val activeProxy = repository.activeProxyFlow.value
            if (activeProxy != null) {
                repository.setBridgeEnabled(true)
                startBridge()
                return START_STICKY
            } else {
                // Must call startForeground before stopping if launched via startForegroundService
                startForegroundCompat(buildNotification(BridgeState.STOPPED, 0))
                stopBridge()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (intent == null) {
            if (repository.isBridgeEnabledFlow.value && repository.activeProxyFlow.value != null) {
                startBridge()
                return START_STICKY
            } else {
                stopBridge()
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startBridge()
        return START_STICKY
    }

    private fun startForegroundCompat(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("BifrostBridgeService", "Failed to start foreground", e)
            false
        }
    }

    private fun startBridge() {
        if (_serviceRunning.value) return

        currentPort = repository.localPortFlow.value
        currentActiveProxy = repository.activeProxyFlow.value

        repository.setBridgeEnabled(true)

        val success = startForegroundCompat(buildNotification(BridgeState.LISTENING, 0))
        if (!success) {
            _serviceRunning.value = false
            _bridgeState.value = BridgeState.STOPPED
            stopSelf()
            return
        }

        _serviceRunning.value = true
        _bridgeState.value = BridgeState.LISTENING

        startServer()
    }

    private fun startServer() {
        socks5Server?.stop()
        socks5Server = Socks5Server(
            port = currentPort,
            proxyConfigProvider = { repository.activeProxyFlow.value },
            onConnectionCountChanged = { count ->
                if (_serviceRunning.value) {
                    _connectionCount.value = count
                    val state = if (count > 0) BridgeState.STREAMING else BridgeState.LISTENING
                    _bridgeState.value = state
                    updateNotification()
                }
            }
        ).also { server ->
            server.start(serviceScope)
        }
    }

    private fun restartServer() {
        socks5Server?.stop()
        startServer()
        updateNotification()
    }

    private fun stopBridge() {
        _serviceRunning.value = false
        _bridgeState.value = BridgeState.STOPPED
        _connectionCount.value = 0
        repository.setBridgeEnabled(false)
        socks5Server?.stop()
        socks5Server = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
    }

    private fun updateNotification() {
        if (!_serviceRunning.value) return
        val notification = buildNotification(_bridgeState.value, _connectionCount.value)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(state: BridgeState, activeConnections: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openTgPendingIntent = com.bifrost.twp.util.TelegramLauncher.createPendingIntent(this, currentPort)

        val contentText = when (state) {
            BridgeState.STREAMING -> getString(R.string.notification_active, activeConnections)
            BridgeState.LISTENING -> getString(R.string.notification_listening, currentPort)
            BridgeState.STOPPED -> getString(R.string.status_stopped)
        }

        val workerInfo = currentActiveProxy?.let { " [${it.name}]" } ?: ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title) + workerInfo)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.action_connect_telegram),
                openTgPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopBridge()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "bifrost_bridge_channel"
        const val NOTIFICATION_ID = 5050
        const val ACTION_START = "com.bifrost.twp.START"
        const val ACTION_STOP = "com.bifrost.twp.STOP"

        private val _serviceRunning = MutableStateFlow(false)
        val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

        private val _bridgeState = MutableStateFlow(BridgeState.STOPPED)
        val bridgeState: StateFlow<BridgeState> = _bridgeState.asStateFlow()

        private val _connectionCount = MutableStateFlow(0)
        val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

        fun start(context: Context) {
            try {
                val appContext = context.applicationContext ?: context
                val intent = Intent(appContext, BifrostBridgeService::class.java).apply {
                    action = ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } catch (e: Exception) {
                android.util.Log.e("BifrostBridgeService", "Failed to start service", e)
            }
        }

        fun stop(context: Context) {
            try {
                val appContext = context.applicationContext ?: context
                val intent = Intent(appContext, BifrostBridgeService::class.java).apply {
                    action = ACTION_STOP
                }
                appContext.startService(intent)
            } catch (e: Exception) {
                android.util.Log.e("BifrostBridgeService", "Failed to stop service", e)
            }
        }
    }
}
