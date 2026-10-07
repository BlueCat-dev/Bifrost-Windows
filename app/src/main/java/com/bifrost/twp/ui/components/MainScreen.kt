package com.bifrost.twp.ui.components

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bifrost.twp.R
import com.bifrost.twp.core.BifrostBridgeService
import com.bifrost.twp.core.BridgeState
import com.bifrost.twp.data.ProxyRepository
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DangerRed
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.util.UpdateChecker
import com.bifrost.twp.util.UpdateResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    repository: ProxyRepository,
    onLaunchQrScanner: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val proxies by repository.proxiesFlow.collectAsState()
    val activeProxy by repository.activeProxyFlow.collectAsState()
    val localPort by repository.localPortFlow.collectAsState()
    val runOnStartup by repository.runOnStartupFlow.collectAsState()
    val appLanguage by repository.appLanguageFlow.collectAsState()
    val hasSelectedLanguage by repository.hasSelectedLanguageFlow.collectAsState()

    val bridgeState by BifrostBridgeService.bridgeState.collectAsState()
    val activeConnections by BifrostBridgeService.connectionCount.collectAsState()
    val isBridgeRunning = bridgeState != BridgeState.STOPPED

    // App Version
    val currentVersionName = remember {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                ).versionName ?: "1.1.5"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.1.5"
            }
        } catch (_: Exception) {
            "1.1.5"
        }
    }

    // Dialog States
    var showAddEditDialog by remember { mutableStateOf(false) }
    var editingProxy by remember { mutableStateOf<ProxyConfig?>(null) }
    var sharingProxy by remember { mutableStateOf<ProxyConfig?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showCreateProxyDialog by remember { mutableStateOf(false) }
    var updateResultToShow by remember { mutableStateOf<UpdateResult?>(null) }

    // Update Check Spinner State
    var isCheckingUpdate by remember { mutableStateOf(false) }
    val infiniteTransition = rememberInfiniteTransition(label = "update_spin")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    // FAB Speed Dial State
    var isFabExpanded by remember { mutableStateOf(false) }

    // Any overlay/popup active -> background blur & scrim
    val isAnyOverlayActive = !hasSelectedLanguage || isFabExpanded || showAddEditDialog || sharingProxy != null ||
            showSettingsDialog || showCreateProxyDialog || updateResultToShow != null

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = DarkBackground,
            modifier = Modifier
                .fillMaxSize()
                .blur(if (isAnyOverlayActive) 16.dp else 0.dp),
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.app_name),
                                color = TextPrimary,
                                fontWeight = FontWeight.Black,
                                letterSpacing = if (appLanguage == "fa") 0.sp else 2.sp,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(NeonCyan)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // App Version Tag
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBackground)
                                    .border(1.dp, CardStroke, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "v$currentVersionName",
                                    color = NeonCyan,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    },
                    actions = {
                        // Check for Updates Button (Spinning animation on click)
                        IconButton(
                            onClick = {
                                if (isCheckingUpdate) return@IconButton
                                isCheckingUpdate = true
                                coroutineScope.launch {
                                    val result = UpdateChecker.checkUpdate(currentVersionName)
                                    isCheckingUpdate = false
                                    result.onSuccess { info ->
                                        if (info.hasUpdate) {
                                            updateResultToShow = info
                                        } else {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.update_toast_latest),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }.onFailure { ex ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.update_toast_error, ex.message ?: context.getString(R.string.msg_network_error)),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Sync,
                                contentDescription = stringResource(R.string.update_dialog_title),
                                tint = if (isCheckingUpdate) NeonCyan else TextSecondary,
                                modifier = Modifier
                                    .size(22.dp)
                                    .graphicsLayer(rotationZ = if (isCheckingUpdate) rotationAngle else 0f)
                            )
                        }

                        // Telegram Channel
                        IconButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Qorvhex_Channel")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_telegram),
                                contentDescription = stringResource(R.string.action_connect_telegram),
                                tint = Color.Unspecified,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // GitHub Repository
                        IconButton(onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Qorvhex/Bifrost")).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_github),
                                contentDescription = stringResource(R.string.update_action_github),
                                tint = Color.Unspecified,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Settings Dialog
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.settings_title),
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = DarkBackground
                    )
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status Header (Port pill removed)
                item {
                    StatusHeader(
                        bridgeState = bridgeState,
                        activeConnections = activeConnections,
                        localPort = localPort,
                        activeProxy = activeProxy
                    )
                }

                // Section Title
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.saved_workers_title),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = stringResource(R.string.configured_count_label, proxies.size),
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                // Empty State
                if (proxies.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.msg_no_proxies),
                                color = TextMuted,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // Proxies List
                items(proxies, key = { it.id }) { config ->
                    ProxyCard(
                        config = config,
                        onSelect = {
                            repository.setActiveProxy(config.id)
                            if (isBridgeRunning) {
                                BifrostBridgeService.start(context)
                            }
                        },
                        onEdit = {
                            editingProxy = config
                            showAddEditDialog = true
                        },
                        onDelete = {
                            repository.deleteProxy(config.id)
                        },
                        onShare = {
                            sharingProxy = config
                        }
                    )
                }

                // Bottom Spacing for FAB
                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }

        // Full Screen Dark Scrim when FAB Speed Dial is expanded
        AnimatedVisibility(
            visible = isFabExpanded,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isFabExpanded = false
                    }
            )
        }

        // FAB and Speed Dial Overlay (Unblurred, rendered on top of Scrim)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 24.dp, end = 20.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Speed Dial Actions Menu
                AnimatedVisibility(
                    visible = isFabExpanded,
                    enter = fadeIn(tween(150)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(200))
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        // 1. Manual Add
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_add_manual),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBackground)
                                    .border(1.dp, CardStroke, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallFloatingActionButton(
                                onClick = {
                                    isFabExpanded = false
                                    editingProxy = null
                                    showAddEditDialog = true
                                },
                                containerColor = CardBackground,
                                contentColor = NeonCyan,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.action_add_manual), modifier = Modifier.size(18.dp))
                            }
                        }

                        // 2. Smart Clipboard Import
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_paste_clipboard),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBackground)
                                    .border(1.dp, CardStroke, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallFloatingActionButton(
                                onClick = {
                                    isFabExpanded = false
                                    importFromClipboard(context, repository, isBridgeRunning)
                                },
                                containerColor = CardBackground,
                                contentColor = NeonEmerald,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = stringResource(R.string.action_paste_clipboard), modifier = Modifier.size(18.dp))
                            }
                        }

                        // 3. Scan QR Code
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_scan_qr),
                                color = TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(CardBackground)
                                    .border(1.dp, CardStroke, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            SmallFloatingActionButton(
                                onClick = {
                                    isFabExpanded = false
                                    onLaunchQrScanner()
                                },
                                containerColor = CardBackground,
                                contentColor = NeonCyan,
                                shape = CircleShape
                            ) {
                                Icon(Icons.Outlined.QrCodeScanner, contentDescription = stringResource(R.string.action_scan_qr), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }

                // Power Toggle Floating Action Button (Turn Proxy On / Off)
                FloatingActionButton(
                    onClick = {
                        if (isBridgeRunning) {
                            repository.setBridgeEnabled(false)
                            BifrostBridgeService.stop(context)
                            Toast.makeText(context, context.getString(R.string.toast_bridge_stopped), Toast.LENGTH_SHORT).show()
                        } else {
                            if (activeProxy != null) {
                                repository.setBridgeEnabled(true)
                                BifrostBridgeService.start(context)
                                Toast.makeText(context, context.getString(R.string.toast_bridge_started), Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.toast_select_worker_first), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    containerColor = CardBackground,
                    contentColor = if (isBridgeRunning) NeonEmerald else DangerRed.copy(alpha = 0.85f),
                    shape = CircleShape,
                    modifier = Modifier
                        .size(56.dp)
                        .border(
                            width = if (isBridgeRunning) 2.dp else 1.2.dp,
                            color = if (isBridgeRunning) NeonEmerald else CardStroke,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.PowerSettingsNew,
                        contentDescription = if (isBridgeRunning) stringResource(R.string.status_stopped) else stringResource(R.string.status_active),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Main FAB
                FloatingActionButton(
                    onClick = { isFabExpanded = !isFabExpanded },
                    containerColor = NeonCyan,
                    contentColor = DarkBackground,
                    shape = CircleShape,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isFabExpanded) Icons.Default.Close else Icons.Default.Add,
                        contentDescription = stringResource(R.string.action_add_proxy),
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }

    // First-launch Language Selection Dialog
    if (!hasSelectedLanguage) {
        LanguageSelectionDialog(
            initialLanguage = appLanguage,
            onLanguageConfirmed = { selectedLang ->
                repository.confirmInitialLanguage(selectedLang)
            }
        )
    }

    // Add / Edit Proxy Dialog
    if (showAddEditDialog) {
        AddEditProxyDialog(
            initialConfig = editingProxy,
            onDismiss = {
                showAddEditDialog = false
                editingProxy = null
            },
            onSave = { updatedConfig ->
                repository.addOrUpdateProxy(updatedConfig, makeActive = true)
                if (isBridgeRunning) {
                    BifrostBridgeService.start(context)
                }
                showAddEditDialog = false
                editingProxy = null
            }
        )
    }

    // Share Modal Dialog
    sharingProxy?.let { config ->
        ShareProxyDialog(
            config = config,
            onDismiss = { sharingProxy = null }
        )
    }

    // Comprehensive Settings Dialog with Language selector
    if (showSettingsDialog) {
        SettingsDialog(
            currentPort = localPort,
            runOnStartup = runOnStartup,
            currentLanguage = appLanguage,
            onDismiss = { showSettingsDialog = false },
            onSavePort = { newPort ->
                repository.setLocalPort(newPort)
                showSettingsDialog = false
                Toast.makeText(context, context.getString(R.string.msg_port_saved), Toast.LENGTH_SHORT).show()
            },
            onToggleRunOnStartup = { enabled ->
                repository.setRunOnStartup(enabled)
                if (enabled) {
                    try {
                        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                        if (powerManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
                                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            },
            onLanguageChanged = { newLang ->
                repository.setAppLanguage(newLang)
            },
            onCreateProxyClick = {
                showCreateProxyDialog = true
            }
        )
    }

    // Create Proxy (Cloudflare) Dialog
    if (showCreateProxyDialog) {
        CreateProxyDialog(
            onDismiss = { showCreateProxyDialog = false },
            onProxyCreated = { config ->
                repository.addOrUpdateProxy(config, makeActive = true)
                if (isBridgeRunning) {
                    BifrostBridgeService.start(context)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_proxy_activated_named, config.name),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    }

    // Update Available Dialog
    updateResultToShow?.let { updateInfo ->
        UpdateDialog(
            updateResult = updateInfo,
            onDismiss = { updateResultToShow = null }
        )
    }
}

private fun importFromClipboard(context: Context, repository: ProxyRepository, isBridgeRunning: Boolean) {
    try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                val imported = repository.importFromLink(text, makeActive = true)
                if (imported != null) {
                    if (isBridgeRunning) {
                        BifrostBridgeService.start(context)
                    }
                    Toast.makeText(context, context.getString(R.string.msg_proxy_saved), Toast.LENGTH_SHORT).show()
                    return
                }
            }
        }
        Toast.makeText(context, context.getString(R.string.msg_invalid_clipboard), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        android.util.Log.e("Bifrost", "Failed to import from clipboard", e)
        Toast.makeText(context, context.getString(R.string.msg_invalid_clipboard), Toast.LENGTH_SHORT).show()
    }
}
