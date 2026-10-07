package com.bifrost.twp.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bifrost.twp.R
import com.bifrost.twp.model.ProxyConfig
import com.bifrost.twp.model.TwpLinkParser
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.LocalAppLanguage
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.NeonEmerald
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.util.QrCodeGenerator

@Composable
fun ShareProxyDialog(
    config: ProxyConfig,
    onDismiss: () -> Unit
) {
    val twpLink = remember(config) { TwpLinkParser.generateLink(config) }

    val qrBitmap: Bitmap? = remember(twpLink) {
        QrCodeGenerator.generateQrBitmap(
            content = twpLink,
            size = 512,
            fgColor = android.graphics.Color.WHITE,
            bgColor = android.graphics.Color.TRANSPARENT
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        BifrostTheme(language = LocalAppLanguage.current) {
            val context = LocalContext.current
            val linkCopiedMsg = stringResource(R.string.msg_copied)
            val twpCopiedMsg = stringResource(R.string.msg_twp_link_copied)
            val shareChooserTitle = stringResource(R.string.share_chooser_title)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(CardBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(18.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dialog_share_title),
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // QR Code Box
            Box(
                modifier = Modifier
                    .size(210.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF090D15))
                    .border(1.dp, CardStroke, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(186.dp)
                    )
                } else {
                    Text(stringResource(R.string.msg_error_qr), color = TextMuted, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Full Link Text Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DarkBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(8.dp))
                    .clickable {
                        copyToClipboard(context, twpLink, twpCopiedMsg)
                    }
                    .padding(10.dp)
            ) {
                Text(
                    text = twpLink,
                    color = NeonCyan,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Copy & Share System Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, twpLink, linkCopiedMsg)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_copy_link), fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        val sendIntent = Intent().apply {
                            action = Intent.ACTION_SEND
                            putExtra(Intent.EXTRA_TEXT, twpLink)
                            type = "text/plain"
                        }
                        val shareIntent = Intent.createChooser(sendIntent, shareChooserTitle)
                        context.startActivity(shareIntent)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBackground
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share_link), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
}

private fun copyToClipboard(context: Context, text: String, message: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Bifrost Proxy Link", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

