package com.bifrost.twp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bifrost.twp.R
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.DangerRed
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.ui.theme.VazirmatnFontFamily

@Composable
fun SettingsDialog(
    currentPort: Int,
    runOnStartup: Boolean,
    currentLanguage: String,
    onDismiss: () -> Unit,
    onSavePort: (Int) -> Unit,
    onToggleRunOnStartup: (Boolean) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onCreateProxyClick: () -> Unit
) {
    var portText by remember { mutableStateOf(currentPort.toString()) }
    var startupEnabled by remember { mutableStateOf(runOnStartup) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        BifrostTheme(language = currentLanguage) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(18.dp))
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_title),
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 1: Create Proxy Button
            Button(
                onClick = {
                    onDismiss()
                    onCreateProxyClick()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan.copy(alpha = 0.15f),
                    contentColor = NeonCyan
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan.copy(alpha = 0.5f))
            ) {
                Icon(
                    imageVector = Icons.Outlined.RocketLaunch,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.settings_create_proxy_btn),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = CardStroke)
            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: Language Selector
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.settings_app_language_title),
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_app_language_desc),
                color = TextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Persian Button
                LanguageChip(
                    title = "فارسی",
                    isSelected = currentLanguage == "fa",
                    isVazir = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onLanguageChanged("fa") }
                )

                // English Button
                LanguageChip(
                    title = "English",
                    isSelected = currentLanguage == "en",
                    isVazir = false,
                    modifier = Modifier.weight(1f),
                    onClick = { onLanguageChanged("en") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = CardStroke)
            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: Run on Startup Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_run_on_startup_title),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.settings_run_on_startup_desc),
                        color = TextMuted,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }

                Switch(
                    checked = startupEnabled,
                    onCheckedChange = {
                        startupEnabled = it
                        onToggleRunOnStartup(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DarkBackground,
                        checkedTrackColor = NeonEmerald,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = DarkBackground
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = CardStroke)
            Spacer(modifier = Modifier.height(16.dp))

            // Section 4: SOCKS5 Port
            Text(
                text = stringResource(R.string.settings_socks5_title),
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_socks5_desc),
                color = TextMuted,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            val portInvalidMsg = stringResource(R.string.msg_port_invalid)
            OutlinedTextField(
                value = portText,
                onValueChange = {
                    portText = it
                    error = null
                },
                label = { Text(stringResource(R.string.field_local_port), fontSize = 12.sp) },
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error!!, color = DangerRed, fontSize = 11.sp) }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                ) {
                    Text(stringResource(R.string.action_cancel), fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = {
                        val parsed = portText.toIntOrNull()
                        if (parsed == null || parsed !in 1024..65535) {
                            error = portInvalidMsg
                            return@Button
                        }
                        onSavePort(parsed)
                    },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBackground
                    )
                ) {
                    Text(stringResource(R.string.action_save), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
}

@Composable
private fun LanguageChip(
    title: String,
    isSelected: Boolean,
    isVazir: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) NeonCyan else CardStroke
    val bgAlpha = if (isSelected) 0.15f else 0.4f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(DarkBackground.copy(alpha = bgAlpha))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            color = if (isSelected) NeonCyan else TextPrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (isVazir) VazirmatnFontFamily else null
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Backward compatibility alias for SettingsPortDialog
 */
@Composable
fun SettingsPortDialog(
    currentPort: Int,
    onDismiss: () -> Unit,
    onSavePort: (Int) -> Unit
) {
    SettingsDialog(
        currentPort = currentPort,
        runOnStartup = false,
        currentLanguage = "en",
        onDismiss = onDismiss,
        onSavePort = onSavePort,
        onToggleRunOnStartup = {},
        onLanguageChanged = {},
        onCreateProxyClick = {}
    )
}
