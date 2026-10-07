package com.bifrost.twp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Launch
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bifrost.twp.R
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DangerRed
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.LocalAppLanguage
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.util.CloudflareDeployer
import kotlinx.coroutines.launch

@Composable
fun CreateProxyDialog(
    onDismiss: () -> Unit,
    onProxyCreated: (ProxyConfig) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var apiToken by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }

    var isDeploying by remember { mutableStateOf(false) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var createdProxy by remember { mutableStateOf<ProxyConfig?>(null) }

    // Direct Cloudflare URL with pre-selected Workers Scripts permissions
    val cfTokenUrl =
        "https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys=%5B%7B%22key%22%3A%22workers_scripts%22%2C%22type%22%3A%22edit%22%7D%5D&accountId=*&zoneId=all&name=Bifrost%20Proxy%20Token"

    Dialog(
        onDismissRequest = {
            if (!isDeploying) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BifrostTheme(language = LocalAppLanguage.current) {
            val emptyTokenMsg = stringResource(R.string.cf_err_empty_token)
            val defaultDeployErrorMsg = stringResource(R.string.cf_err_deploy_failed)

            Box(
                modifier = Modifier
                    .padding(20.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(20.dp))
                    .padding(20.dp)
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Outlined.RocketLaunch,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.cf_dialog_title),
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tutorial / Guide Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(DarkBackground.copy(alpha = 0.6f))
                        .border(1.dp, CardStroke.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.cf_guide_header),
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.cf_guide_body),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(cfTokenUrl)).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan.copy(alpha = 0.15f),
                            contentColor = NeonCyan
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Launch,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.cf_btn_open_cloudflare),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Input: API Token
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = {
                        apiToken = it
                        errorMessage = null
                    },
                    label = { Text(stringResource(R.string.cf_field_token_label), fontSize = 12.sp) },
                    placeholder = { Text(stringResource(R.string.cf_field_token_placeholder), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Key, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    enabled = !isDeploying && createdProxy == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CardStroke,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = NeonCyan,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = NeonCyan
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Input: Optional Secret Key
                OutlinedTextField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = { Text(stringResource(R.string.cf_field_secret_label), fontSize = 12.sp) },
                    placeholder = { Text(stringResource(R.string.cf_field_secret_placeholder), fontSize = 11.sp) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = TextMuted, modifier = Modifier.size(18.dp))
                    },
                    singleLine = true,
                    enabled = !isDeploying && createdProxy == null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = CardStroke,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = NeonCyan,
                        unfocusedLabelColor = TextSecondary,
                        cursorColor = NeonCyan
                    )
                )

                // Deployment Progress Indicator
                AnimatedVisibility(visible = isDeploying) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = statusMessage,
                                color = NeonCyan,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "$progressPercent%",
                                color = NeonCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progressPercent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = NeonCyan,
                            trackColor = DarkBackground
                        )
                    }
                }

                // Error Message Display
                errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(DangerRed.copy(alpha = 0.15f))
                            .border(1.dp, DangerRed.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = DangerRed,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = err,
                            color = DangerRed,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }

                // Success Message Display
                createdProxy?.let { proxy ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(NeonEmerald.copy(alpha = 0.15f))
                            .border(1.dp, NeonEmerald.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = NeonEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.cf_success_title),
                                color = NeonEmerald,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.cf_success_desc, proxy.workerHost),
                            color = TextPrimary,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Dialog Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        enabled = !isDeploying,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                    ) {
                        Text(
                            text = if (createdProxy != null) stringResource(R.string.action_close) else stringResource(R.string.action_cancel),
                            fontSize = 13.sp
                        )
                    }

                    if (createdProxy == null) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Button(
                            onClick = {
                                if (apiToken.isBlank()) {
                                    errorMessage = emptyTokenMsg
                                    return@Button
                                }
                                errorMessage = null
                                isDeploying = true
                                progressPercent = 0
                                statusMessage = "..."

                                coroutineScope.launch {
                                    val result = CloudflareDeployer.deployWorker(
                                        apiToken = apiToken,
                                        secretKey = secretKey,
                                        onProgress = { percent, msg ->
                                            progressPercent = percent
                                            statusMessage = msg
                                        }
                                    )

                                    isDeploying = false
                                    result.onSuccess { config ->
                                        createdProxy = config
                                        onProxyCreated(config)
                                    }.onFailure { ex ->
                                        errorMessage = ex.message ?: defaultDeployErrorMsg
                                    }
                                }
                            },
                            enabled = !isDeploying && apiToken.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NeonCyan,
                                contentColor = DarkBackground
                            )
                        ) {
                            Text(
                                text = stringResource(R.string.cf_btn_create_submit),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
}
