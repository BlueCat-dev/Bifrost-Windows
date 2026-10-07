package com.bifrost.twp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bifrost.twp.R
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.DangerRed
import com.bifrost.twp.ui.theme.LocalAppLanguage
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary

@Composable
fun AddEditProxyDialog(
    initialConfig: ProxyConfig? = null,
    onDismiss: () -> Unit,
    onSave: (ProxyConfig) -> Unit
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var workerHost by remember { mutableStateOf(initialConfig?.workerHost ?: "") }
    var cleanIp by remember { mutableStateOf(initialConfig?.cleanIp ?: "") }
    var secret by remember { mutableStateOf(initialConfig?.secret ?: "") }

    var hostError by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        BifrostTheme(language = LocalAppLanguage.current) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(18.dp))
                    .padding(20.dp)
            ) {
            Text(
                text = if (initialConfig == null) stringResource(R.string.dialog_add_proxy_title) else stringResource(R.string.dialog_edit_proxy_title),
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Config Name Field
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Worker Host Field (Mandatory)
            OutlinedTextField(
                value = workerHost,
                onValueChange = {
                    workerHost = it
                    if (it.isNotBlank()) hostError = false
                },
                label = { Text(stringResource(R.string.field_worker_host), fontSize = 12.sp) },
                isError = hostError,
                supportingText = if (hostError) {
                    { Text(stringResource(R.string.msg_error_empty_host), color = DangerRed, fontSize = 11.sp) }
                } else null,
                singleLine = true,
                placeholder = { Text("proxy.workers.dev", color = TextMuted, fontSize = 12.sp) },
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Clean IP Field (Optional)
            OutlinedTextField(
                value = cleanIp,
                onValueChange = { cleanIp = it },
                label = { Text(stringResource(R.string.field_clean_ip), fontSize = 12.sp) },
                placeholder = { Text("104.16.132.229", color = TextMuted, fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Secret Field (Optional)
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.field_secret), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = textFieldColors()
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action Buttons
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
                        val cleanHost = workerHost.trim()
                        if (cleanHost.isBlank()) {
                            hostError = true
                            return@Button
                        }

                        val finalName = if (name.isNotBlank()) name.trim() else cleanHost

                        val config = initialConfig?.copy(
                            name = finalName,
                            workerHost = cleanHost,
                            cleanIp = cleanIp.trim().takeIf { it.isNotBlank() },
                            secret = secret.trim().takeIf { it.isNotBlank() }
                        ) ?: ProxyConfig(
                            name = finalName,
                            workerHost = cleanHost,
                            cleanIp = cleanIp.trim().takeIf { it.isNotBlank() },
                            secret = secret.trim().takeIf { it.isNotBlank() },
                            port = 443
                        )

                        onSave(config)
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
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NeonCyan,
    unfocusedBorderColor = CardStroke,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = NeonCyan,
    unfocusedLabelColor = TextSecondary,
    cursorColor = NeonCyan
)
