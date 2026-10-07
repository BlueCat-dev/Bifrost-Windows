package com.bifrost.twp.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bifrost.twp.R
import com.bifrost.twp.core.BridgeState
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.ui.theme.WarningAmber

@Composable
fun StatusHeader(
    bridgeState: BridgeState,
    activeConnections: Int,
    localPort: Int,
    activeProxy: ProxyConfig?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val stateColor = when (bridgeState) {
        BridgeState.STREAMING -> NeonCyan
        BridgeState.LISTENING -> NeonEmerald
        BridgeState.STOPPED -> TextMuted
    }

    val stateText = when (bridgeState) {
        BridgeState.STREAMING -> stringResource(R.string.status_streaming_badge, activeConnections)
        BridgeState.LISTENING -> stringResource(R.string.status_ready_badge)
        BridgeState.STOPPED -> stringResource(R.string.status_standby_badge)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBackground)
            .border(1.dp, CardStroke, RoundedCornerShape(16.dp))
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Top Row: Status badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Pill with subtle glow/pulse
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(stateColor.copy(alpha = 0.12f))
                        .border(1.dp, stateColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stateText,
                        color = stateColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Active Worker Information
            if (activeProxy != null) {
                Text(
                    text = activeProxy.name,
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = activeProxy.workerHost,
                    color = NeonCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )

                if (!activeProxy.cleanIp.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.clean_ip_label, activeProxy.cleanIp),
                        color = NeonEmerald,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.no_active_worker_title),
                    color = WarningAmber,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(R.string.no_active_worker_sub),
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Connect to Telegram Button
            Button(
                onClick = {
                    com.bifrost.twp.util.TelegramLauncher.openTelegramSocks(context, localPort)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBackground
                ),
                enabled = activeProxy != null
            ) {
                Icon(
                    imageVector = Icons.Outlined.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.action_connect_telegram),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
