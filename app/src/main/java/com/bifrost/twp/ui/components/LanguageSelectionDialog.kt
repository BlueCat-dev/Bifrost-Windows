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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bifrost.twp.R
import com.bifrost.twp.ui.theme.BifrostTheme
import com.bifrost.twp.ui.theme.CardBackground
import com.bifrost.twp.ui.theme.CardStroke
import com.bifrost.twp.ui.theme.DarkBackground
import com.bifrost.twp.ui.theme.NeonCyan
import com.bifrost.twp.ui.theme.TextMuted
import com.bifrost.twp.ui.theme.TextPrimary
import com.bifrost.twp.ui.theme.TextSecondary
import com.bifrost.twp.ui.theme.VazirmatnFontFamily

@Composable
fun LanguageSelectionDialog(
    initialLanguage: String = "fa",
    onLanguageConfirmed: (String) -> Unit
) {
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }

    Dialog(
        onDismissRequest = {}, // Non-dismissible on first run
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        BifrostTheme(language = selectedLanguage) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(CardBackground)
                    .border(1.dp, CardStroke, RoundedCornerShape(20.dp))
                    .padding(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Header
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.lang_selection_title),
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = stringResource(R.string.lang_selection_subtitle),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Option 1: Persian
                    LanguageOptionCard(
                        title = "فارسی",
                        subtitle = "رابط کاربری زبان فارسی",
                        isSelected = selectedLanguage == "fa",
                        isVazir = true,
                        onClick = { selectedLanguage = "fa" }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Option 2: English
                    LanguageOptionCard(
                        title = "English",
                        subtitle = "English user interface",
                        isSelected = selectedLanguage == "en",
                        isVazir = false,
                        onClick = { selectedLanguage = "en" }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Confirm Button
                    Button(
                        onClick = { onLanguageConfirmed(selectedLanguage) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = DarkBackground
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_confirm_start),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LanguageOptionCard(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    isVazir: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) NeonCyan else CardStroke
    val bgAlpha = if (isSelected) 0.12f else 0.4f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(DarkBackground.copy(alpha = bgAlpha))
            .border(if (isSelected) 1.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isSelected) NeonCyan else TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = if (isVazir) VazirmatnFontFamily else null
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = if (isVazir) VazirmatnFontFamily else null
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .border(1.5.dp, if (isSelected) NeonCyan else TextMuted, CircleShape)
                .background(if (isSelected) NeonCyan else DarkBackground),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = DarkBackground,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
